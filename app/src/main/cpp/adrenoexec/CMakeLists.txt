cmake_minimum_required(VERSION 3.22.1)
project(adrenoexec C)

# LD_PRELOAD exec shim: runs app-data executables through /system/bin/linker64.
add_library(adrenoexec SHARED adrenoexec.c)
target_compile_options(adrenoexec PRIVATE -O2 -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(adrenoexec PRIVATE dl)
target_link_options(adrenoexec PRIVATE -Wl,-z,max-page-size=16384)
