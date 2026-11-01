#!/bin/sh
# Exercises adrenoexec's rewrite logic on a glibc x86_64/aarch64 host, using the glibc dynamic
# loader in place of /system/bin/linker64 (both accept "loader /path/prog args").
set -eu
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
ldso=$(readelf -l /bin/true | sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p')
cc -shared -fPIC -O2 -Wall -Wextra -Wno-nonnull-compare -Wno-format-truncation -fvisibility=hidden \
   -DLINKER64="\"$ldso\"" -DINTERP_MATCH="\"ld-linux\"" -DELF_MACHINE="$(uname -m | sed "s/x86_64/EM_X86_64/;s/aarch64/EM_AARCH64/")" \
   "$here/../adrenoexec.c" -ldl -o "$work/libadrenoexec.so"

cat > "$work/prog.c" <<'C'
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>
#include <fcntl.h>
int main(int argc, char **argv) {
    char exe[PATH_MAX] = {0};
    if (readlink("/proc/self/exe", exe, sizeof(exe) - 1) < 0) return 3;
    char cmd[PATH_MAX] = {0};
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0 || read(fd, cmd, sizeof(cmd) - 1) < 0) return 4;
    int via_loader = strstr(cmd, "ld-linux") != NULL;
    const char *pre = getenv("LD_PRELOAD");
    printf("exe=%s argc=%d", exe, argc);
    for (int i = 1; i < argc; i++) printf(" [%s]", argv[i]);
    printf(" preload=%s loader=%d\n", pre && *pre ? "yes" : "no", via_loader);
    return 0;
}
C
cc -O2 "$work/prog.c" -o "$work/prog"
cat > "$work/script.sh" <<S
#!/bin/sh -e
exec "$work/prog" from-script "\$@"
S
chmod +x "$work/script.sh"
cat > "$work/spawn.c" <<'C'
#include <spawn.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>
extern char **environ;
int main(int argc, char **argv) {
    (void)argc;
    pid_t pid; int st;
    char *a[] = {argv[1], "spawned", NULL};
    char *env[] = {"PATH=/usr/bin:/bin", NULL};  /* no LD_PRELOAD: shim must re-add it */
    if (posix_spawn(&pid, argv[1], NULL, NULL, a, env) != 0) return 1;
    waitpid(pid, &st, 0);
    char *b[] = {argv[1], "execd", NULL};
    execve(argv[1], b, env);
    return 2;
}
C
cc -O2 "$work/spawn.c" -o "$work/spawn"

out=$(LD_PRELOAD="$work/libadrenoexec.so" "$work/spawn" "$work/prog")
echo "$out"
echo "$out" | grep -q "exe=$work/prog argc=2 \[spawned\] preload=yes loader=1"
echo "$out" | grep -q "exe=$work/prog argc=2 \[execd\] preload=yes loader=1"

out=$(LD_PRELOAD="$work/libadrenoexec.so" /bin/sh -c "\"$work/script.sh\" a 'b c'")
echo "$out"
echo "$out" | grep -q "exe=$work/prog argc=4 \[from-script\] \[a\] \[b c\] preload=yes loader=1"

# /proc/self/exe answered with the real program when launched through the loader directly.
out=$(LD_PRELOAD="$work/libadrenoexec.so" "$ldso" "$work/prog" direct)
echo "$out"
echo "$out" | grep -q "exe=$work/prog argc=2 \[direct\]"
echo "adrenoexec host test: OK"
