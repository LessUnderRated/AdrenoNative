// SPDX-License-Identifier: GPL-3.0-or-later
//
// adrenoexec: open replacement for GameNative's closed libredirect exec handling.
//
// Apps targeting API 29+ may not execve() files in their own data directory (SELinux denies
// execute_no_trans on app_data_file), but they may still mmap them executable. So, like
// termux-exec (Apache-2.0), this LD_PRELOAD library rewrites
//     execve("/data/.../bin/foo", argv, envp)
// to
//     execve("/system/bin/linker64", ["/system/bin/linker64", "/data/.../bin/foo", argv[1..]], envp)
// for bionic (PT_INTERP=linker64) executables outside the system partitions, follows "#!" scripts,
// and keeps itself in LD_PRELOAD so grandchildren are covered too.
//
// Programs started through linker64 see /proc/self/exe as the linker; readlink()/realpath() of
// /proc/self/exe are answered with the real program path (wine uses it to find its libraries).

#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))
#ifndef LINKER64  // overridable so the logic can be exercised on a glibc host (see test/)
#define LINKER64 "/system/bin/linker64"
#endif
#ifndef ELF_MACHINE
#define ELF_MACHINE EM_AARCH64
#endif
#ifndef INTERP_MATCH
#define INTERP_MATCH "linker64"
#endif
#define MAX_SCRIPT_DEPTH 4

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);
typedef char *(*realpath_fn)(const char *, char *);

static execve_fn real_execve;
static spawn_fn real_posix_spawn;
static readlink_fn real_readlink;
static readlinkat_fn real_readlinkat;
static realpath_fn real_realpath;

static char g_shim_path[PATH_MAX];  // this library, kept in children's LD_PRELOAD
static char g_self_exe[PATH_MAX];   // real program path when started via linker64

extern char **environ;

static void resolve_real(void) {
    if (real_execve) return;
    real_execve = (execve_fn)dlsym(RTLD_NEXT, "execve");
    real_posix_spawn = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    real_readlink = (readlink_fn)dlsym(RTLD_NEXT, "readlink");
    real_readlinkat = (readlinkat_fn)dlsym(RTLD_NEXT, "readlinkat");
    real_realpath = (realpath_fn)dlsym(RTLD_NEXT, "realpath");
}

static bool starts_with(const char *s, const char *prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

// Files on these partitions are exec-able as-is.
static bool is_system_path(const char *p) {
    static const char *const prefixes[] = {
        "/system/", "/apex/", "/vendor/", "/product/", "/odm/", "/system_ext/", "/bin/", NULL,
    };
    for (int i = 0; prefixes[i]; i++)
        if (starts_with(p, prefixes[i])) return true;
    return false;
}

static void make_absolute(const char *path, char *out, size_t size) {
    if (path[0] == '/') {
        snprintf(out, size, "%s", path);
        return;
    }
    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof(cwd))) {
        snprintf(out, size, "%s", path);
        return;
    }
    snprintf(out, size, "%s/%s", cwd, path);
}

static bool is_proc_self_exe(const char *path) {
    if (!path) return false;
    if (strcmp(path, "/proc/self/exe") == 0 || strcmp(path, "/proc/thread-self/exe") == 0) return true;
    char own[64];
    snprintf(own, sizeof(own), "/proc/%d/exe", getpid());
    return strcmp(path, own) == 0;
}

static size_t count_args(char *const v[]) {
    size_t n = 0;
    if (v)
        while (v[n]) n++;
    return n;
}

static const char *env_lookup(char *const envp[], const char *name) {
    size_t len = strlen(name);
    for (size_t i = 0; envp && envp[i]; i++)
        if (strncmp(envp[i], name, len) == 0 && envp[i][len] == '=') return envp[i] + len + 1;
    return NULL;
}

static bool list_contains(const char *list, const char *item) {
    size_t len = strlen(item);
    for (const char *p = list; (p = strstr(p, item)); p += len) {
        bool start_ok = p == list || p[-1] == ':' || p[-1] == ' ';
        bool end_ok = p[len] == '\0' || p[len] == ':' || p[len] == ' ';
        if (start_ok && end_ok) return true;
    }
    return false;
}

// Returns envp, or a malloc'd copy whose LD_PRELOAD includes this shim.
static char **env_with_shim(char *const envp[], bool *allocated) {
    *allocated = false;
    if (!g_shim_path[0]) return (char **)envp;
    const char *cur = env_lookup(envp, "LD_PRELOAD");
    if (cur && list_contains(cur, g_shim_path)) return (char **)envp;

    size_t n = count_args(envp);
    char **out = calloc(n + 2, sizeof(char *));
    if (!out) return (char **)envp;
    size_t j = 0;
    for (size_t i = 0; i < n; i++)
        if (strncmp(envp[i], "LD_PRELOAD=", 11) != 0) out[j++] = envp[i];
    size_t size = strlen(g_shim_path) + (cur ? strlen(cur) : 0) + 16;
    char *entry = malloc(size);
    if (!entry) {
        free(out);
        return (char **)envp;
    }
    if (cur && *cur)
        snprintf(entry, size, "LD_PRELOAD=%s:%s", cur, g_shim_path);
    else
        snprintf(entry, size, "LD_PRELOAD=%s", g_shim_path);
    out[j++] = entry;
    out[j] = NULL;
    *allocated = true;
    return out;
}

static void free_env(char **env, bool allocated) {
    if (!allocated) return;
    size_t n = count_args(env);
    if (n) free(env[n - 1]);  // env_with_shim appends its LD_PRELOAD entry last
    free(env);
}

// Finds an interpreter named on a "#!" line that does not exist on Android (/bin/sh,
// /usr/bin/env, ...) under $PREFIX (imagefs/usr) or /system/bin.
static void resolve_interpreter(const char *interp, char *const envp[], char *out, size_t size) {
    snprintf(out, size, "%s", interp);
    if (access(interp, F_OK) == 0) return;
    const char *prefix = env_lookup(envp, "PREFIX");
    if (!prefix) prefix = getenv("PREFIX");
    char candidate[PATH_MAX];
    if (prefix) {
        const char *rest = NULL;
        if (starts_with(interp, "/usr/")) rest = interp + 4;
        else if (starts_with(interp, "/bin/")) rest = interp;
        if (rest) {
            snprintf(candidate, sizeof(candidate), "%s%s", prefix, rest);
            if (access(candidate, F_OK) == 0) {
                snprintf(out, size, "%s", candidate);
                return;
            }
        }
    }
    const char *base = strrchr(interp, '/');
    snprintf(candidate, sizeof(candidate), "/system/bin/%s", base ? base + 1 : interp);
    if (access(candidate, F_OK) == 0) snprintf(out, size, "%s", candidate);
}

enum kind { KIND_OTHER, KIND_BIONIC_ELF, KIND_SCRIPT };

// Classifies a file. For scripts fills interp/arg from the "#!" line (Linux semantics: one
// optional argument, the rest of the line).
static enum kind classify(const char *path, char *interp, size_t interp_size, char *arg, size_t arg_size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return KIND_OTHER;
    unsigned char head[256];
    ssize_t n = pread(fd, head, sizeof(head) - 1, 0);
    enum kind result = KIND_OTHER;

    if (n >= 2 && head[0] == '#' && head[1] == '!') {
        head[n] = '\0';
        char *line = (char *)head + 2;
        char *nl = strchr(line, '\n');
        if (nl) *nl = '\0';
        while (*line == ' ' || *line == '\t') line++;
        char *end = line;
        while (*end && *end != ' ' && *end != '\t') end++;
        char *rest = end;
        if (*rest) {
            *rest++ = '\0';
            while (*rest == ' ' || *rest == '\t') rest++;
            size_t len = strlen(rest);
            while (len && (rest[len - 1] == ' ' || rest[len - 1] == '\t' || rest[len - 1] == '\r')) rest[--len] = '\0';
        }
        size_t ilen = strlen(line);
        while (ilen && line[ilen - 1] == '\r') line[--ilen] = '\0';
        if (*line) {
            snprintf(interp, interp_size, "%s", line);
            snprintf(arg, arg_size, "%s", rest);
            result = KIND_SCRIPT;
        }
    } else if (n >= (ssize_t)sizeof(Elf64_Ehdr) && memcmp(head, ELFMAG, SELFMAG) == 0 &&
               head[EI_CLASS] == ELFCLASS64) {
        Elf64_Ehdr eh;
        memcpy(&eh, head, sizeof(eh));
        if (eh.e_machine == ELF_MACHINE && eh.e_phentsize == sizeof(Elf64_Phdr)) {
            for (int i = 0; i < eh.e_phnum && i < 64; i++) {
                Elf64_Phdr ph;
                if (pread(fd, &ph, sizeof(ph), eh.e_phoff + (off_t)i * sizeof(ph)) != sizeof(ph)) break;
                if (ph.p_type != PT_INTERP) continue;
                char ip[PATH_MAX] = {0};
                size_t len = ph.p_filesz < sizeof(ip) - 1 ? ph.p_filesz : sizeof(ip) - 1;
                if (pread(fd, ip, len, ph.p_offset) == (ssize_t)len && strstr(ip, INTERP_MATCH))
                    result = KIND_BIONIC_ELF;
                break;
            }
        }
    }
    close(fd);
    return result;
}

struct rewrite {
    const char *path;
    char **argv;
    void *to_free[8];
    int nfree;
};

static void rewrite_free(struct rewrite *rw) {
    for (int i = 0; i < rw->nfree; i++) free(rw->to_free[i]);
    rw->nfree = 0;
}

static void *track(struct rewrite *rw, void *p) {
    if (p && rw->nfree < (int)(sizeof(rw->to_free) / sizeof(rw->to_free[0]))) rw->to_free[rw->nfree++] = p;
    return p;
}

// Computes the path/argv to actually exec. Leaves rw->path/argv pointing at the input when no
// rewrite applies.
static void prepare(const char *path, char *const argv[], char *const envp[], struct rewrite *rw, int depth) {
    if (!rw->path) {
        rw->path = path;
        rw->argv = (char **)argv;
    }
    if (!path || is_system_path(path) || depth > MAX_SCRIPT_DEPTH) return;

    char interp[PATH_MAX], arg[256];
    enum kind k = classify(path, interp, sizeof(interp), arg, sizeof(arg));
    size_t argc = count_args(argv);

    if (k == KIND_BIONIC_ELF) {
        char *abs = track(rw, malloc(PATH_MAX));
        char **nv = track(rw, calloc(argc + 2, sizeof(char *)));
        if (!abs || !nv) return;
        make_absolute(path, abs, PATH_MAX);
        nv[0] = LINKER64;
        nv[1] = abs;
        for (size_t i = 1; i < argc; i++) nv[i + 1] = argv[i];
        rw->path = LINKER64;
        rw->argv = nv;
    } else if (k == KIND_SCRIPT) {
        char *ip = track(rw, malloc(PATH_MAX));
        char *ap = arg[0] ? track(rw, strdup(arg)) : NULL;
        char *sp = track(rw, strdup(path));
        char **nv = track(rw, calloc(argc + 3, sizeof(char *)));
        if (!ip || !sp || !nv || (arg[0] && !ap)) return;
        resolve_interpreter(interp, envp, ip, PATH_MAX);
        size_t j = 0;
        nv[j++] = ip;
        if (ap) nv[j++] = ap;
        nv[j++] = sp;
        for (size_t i = 1; i < argc; i++) nv[j++] = argv[i];
        rw->path = ip;
        rw->argv = nv;
        // The interpreter itself may live in app data (e.g. imagefs/usr/bin/bash).
        struct rewrite inner = {0};
        prepare(ip, nv, envp, &inner, depth + 1);
        if (inner.path != ip) {
            rw->path = inner.path;
            rw->argv = inner.argv;
            for (int i = 0; i < inner.nfree; i++) track(rw, inner.to_free[i]);
        } else {
            rewrite_free(&inner);
        }
    }
}

EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
    resolve_real();
    struct rewrite rw = {0};
    prepare(path, argv, envp, &rw, 0);
    bool env_alloc;
    char **env = env_with_shim(envp, &env_alloc);
    int rc = real_execve(rw.path, rw.argv, env);
    int saved = errno;
    free_env(env, env_alloc);
    rewrite_free(&rw);
    errno = saved;
    return rc;
}

EXPORT int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

static int search_path(const char *file, char *const envp[], char *out, size_t size) {
    const char *path = env_lookup(envp, "PATH");
    if (!path) path = getenv("PATH");
    if (!path) path = "/system/bin:/system/xbin";
    const char *p = path;
    while (*p) {
        const char *colon = strchr(p, ':');
        size_t len = colon ? (size_t)(colon - p) : strlen(p);
        snprintf(out, size, "%.*s%s%s", (int)len, p, len ? "/" : "", file);
        if (access(out, X_OK) == 0) return 0;
        if (!colon) break;
        p = colon + 1;
    }
    return -1;
}

EXPORT int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) {
        errno = ENOENT;
        return -1;
    }
    if (strchr(file, '/')) return execve(file, argv, envp);
    char full[PATH_MAX];
    if (search_path(file, envp, full, sizeof(full)) != 0) {
        errno = ENOENT;
        return -1;
    }
    execve(full, argv, envp);
    if (errno == ENOEXEC) {
        // POSIX: a file without a recognised header is run by the shell.
        size_t argc = count_args(argv);
        char **nv = calloc(argc + 2, sizeof(char *));
        if (!nv) return -1;
        nv[0] = "/system/bin/sh";
        nv[1] = full;
        for (size_t i = 1; i < argc; i++) nv[i + 1] = argv[i];
        execve(nv[0], nv, envp);
        int saved = errno;
        free(nv);
        errno = saved;
    }
    return -1;
}

EXPORT int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }

#define COLLECT_ARGS(first, ap, out)                                 \
    do {                                                                    \
        size_t n_ = 1;                                                      \
        va_list c_;                                                         \
        va_copy(c_, ap);                                                    \
        while (va_arg(c_, char *)) n_++;                                    \
        va_end(c_);                                                         \
        out = alloca((n_ + 1) * sizeof(char *));                            \
        out[0] = (char *)(first);                                           \
        for (size_t i_ = 1; i_ <= n_; i_++) out[i_] = va_arg(ap, char *);   \
    } while (0)

EXPORT int execl(const char *path, const char *arg0, ...) {
    va_list ap;
    va_start(ap, arg0);
    char **argv;
    COLLECT_ARGS(arg0, ap, argv);
    va_end(ap);
    return execve(path, argv, environ);
}

EXPORT int execlp(const char *file, const char *arg0, ...) {
    va_list ap;
    va_start(ap, arg0);
    char **argv;
    COLLECT_ARGS(arg0, ap, argv);
    va_end(ap);
    return execvpe(file, argv, environ);
}

EXPORT int execle(const char *path, const char *arg0, ...) {
    va_list ap;
    va_start(ap, arg0);
    char **argv;
    char **envp = NULL;
    COLLECT_ARGS(arg0, ap, argv);
    envp = va_arg(ap, char **);
    va_end(ap);
    return execve(path, argv, envp);
}

static int spawn_common(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                        const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    resolve_real();
    struct rewrite rw = {0};
    prepare(path, argv, envp, &rw, 0);
    bool env_alloc;
    char **env = env_with_shim(envp ? envp : environ, &env_alloc);
    int rc = real_posix_spawn(pid, rw.path, fa, attr, rw.argv, env);
    free_env(env, env_alloc);
    rewrite_free(&rw);
    return rc;
}

EXPORT int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                       const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    return spawn_common(pid, path, fa, attr, argv, envp);
}

EXPORT int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                        const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (!file || !*file) return ENOENT;
    if (strchr(file, '/')) return spawn_common(pid, file, fa, attr, argv, envp);
    char full[PATH_MAX];
    if (search_path(file, envp ? envp : environ, full, sizeof(full)) != 0) return ENOENT;
    return spawn_common(pid, full, fa, attr, argv, envp);
}

static ssize_t answer_self_exe(char *buf, size_t size) {
    size_t len = strlen(g_self_exe);
    if (len > size) len = size;
    memcpy(buf, g_self_exe, len);  // readlink does not NUL-terminate
    return (ssize_t)len;
}

EXPORT ssize_t readlink(const char *path, char *buf, size_t size) {
    resolve_real();
    if (g_self_exe[0] && is_proc_self_exe(path)) return answer_self_exe(buf, size);
    return real_readlink(path, buf, size);
}

EXPORT ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    resolve_real();
    if (g_self_exe[0] && is_proc_self_exe(path)) return answer_self_exe(buf, size);
    return real_readlinkat(dirfd, path, buf, size);
}

EXPORT char *realpath(const char *path, char *resolved) {
    resolve_real();
    if (g_self_exe[0] && is_proc_self_exe(path)) path = g_self_exe;
    return real_realpath(path, resolved);
}

// When started as "linker64 /path/prog args", /proc/self/cmdline still holds the linker's argv:
// the second entry is the program.
static void detect_self_exe(void) {
    char exe[PATH_MAX];
    ssize_t n = real_readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0) return;
    exe[n] = '\0';
    const char *base = strrchr(exe, '/');
    if (!base || strcmp(base + 1, strrchr(LINKER64, '/') + 1) != 0) return;

    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    char cmd[PATH_MAX * 2];
    ssize_t len = read(fd, cmd, sizeof(cmd) - 1);
    close(fd);
    if (len <= 0) return;
    cmd[len] = '\0';
    size_t first = strlen(cmd) + 1;
    if ((ssize_t)first >= len || !cmd[first]) return;
    char abs[PATH_MAX];
    make_absolute(cmd + first, abs, sizeof(abs));
    if (!real_realpath(abs, g_self_exe)) snprintf(g_self_exe, sizeof(g_self_exe), "%s", abs);
}

__attribute__((constructor)) static void adrenoexec_init(void) {
    resolve_real();
    Dl_info info;
    if (dladdr((void *)&adrenoexec_init, &info) && info.dli_fname) {
        if (!real_realpath(info.dli_fname, g_shim_path))
            snprintf(g_shim_path, sizeof(g_shim_path), "%s", info.dli_fname);
    }
    detect_self_exe();
}
