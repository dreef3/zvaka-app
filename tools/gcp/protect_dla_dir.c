/*
 * protect_dla_dir.c — LD_PRELOAD interceptor that prevents the compiler plugin
 * from deleting MTKNN_ADAPTER_DLA_DIR before NeuronAdapter checks it.
 *
 * Root causes discovered:
 * 1. MTKNN_ADAPTER_DLA_DIR is set to a RELATIVE path by the plugin (e.g.
 *    "tmp/tempdir_dla.XYZ") but deletions use the ABSOLUTE path.
 * 2. The plugin does NOT use setenv() — it may use putenv() or direct environ
 *    writes, so caching via a setenv interceptor is unreliable.
 *
 * Fix: in every deletion interceptor, re-read getenv("MTKNN_ADAPTER_DLA_DIR")
 * fresh, resolve it to an absolute path, and mkdir it if it disappeared.
 * This works regardless of how the plugin set the env var.
 *
 * Build: gcc -shared -fPIC -o protect_dla_dir.so protect_dla_dir.c -ldl
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <limits.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <errno.h>

/* Resolve MTKNN_ADAPTER_DLA_DIR to an absolute path.
 * Returns 1 if successful, 0 otherwise. */
static int get_abs_dla(char *out, size_t outsz) {
    const char *dla = getenv("MTKNN_ADAPTER_DLA_DIR");
    if (!dla || dla[0] == '\0') return 0;

    if (dla[0] == '/') {
        strncpy(out, dla, outsz - 1);
        out[outsz - 1] = '\0';
        return 1;
    }
    /* Relative path — resolve using CWD. */
    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof(cwd))) {
        snprintf(out, outsz, "/%s", dla);
    } else if (strcmp(cwd, "/") == 0) {
        snprintf(out, outsz, "/%s", dla);
    } else {
        snprintf(out, outsz, "%s/%s", cwd, dla);
    }
    return 1;
}

/* After any deletion, ensure MTKNN_ADAPTER_DLA_DIR still exists. */
static void protect_after_deletion(const char *deleted_path) {
    char abs_dla[PATH_MAX];
    if (!get_abs_dla(abs_dla, sizeof(abs_dla))) return;

    struct stat st;
    if (stat(abs_dla, &st) != 0) {
        /* Directory is gone — recreate it. */
        if (mkdir(abs_dla, 0755) == 0 || errno == EEXIST) {
            fprintf(stderr,
                    "[DLA-PROTECT] Recreated '%s' after removal of '%s'\n",
                    abs_dla, deleted_path ? deleted_path : "(unlinkat)");
        }
    }
}

/* ── dlopen interceptor: strip RTLD_DEEPBIND so our rmdir/unlink symbols
 *    take precedence over the library's private resolution. ────────────── */

void *dlopen(const char *filename, int flags) {
    static void *(*real)(const char *, int) = NULL;
    if (!real) real = dlsym(RTLD_NEXT, "dlopen");

    /* Redirect bare "libneuron_adapter.so" to an override path.
     * v8_0_10 rejects 1-input RESHAPE ("Got 1 of 2"); v9_0_3 lacks this check.
     * Set NEURON_ADAPTER_OVERRIDE_SO=/path/to/v9_0_3/host/lib/libneuron_adapter.so */
    const char *override_so = getenv("NEURON_ADAPTER_OVERRIDE_SO");
    if (override_so && filename && strcmp(filename, "libneuron_adapter.so") == 0) {
        fprintf(stderr,
                "[DLA-PROTECT] Redirecting libneuron_adapter.so -> %s\n",
                override_so);
        return real(override_so, flags & ~RTLD_DEEPBIND);
    }

    /* Strip RTLD_DEEPBIND from all other libs. */
    if (flags & RTLD_DEEPBIND) {
        fprintf(stderr,
                "[DLA-PROTECT] Stripping RTLD_DEEPBIND from dlopen('%s')\n",
                filename ? filename : "(null)");
        flags &= ~RTLD_DEEPBIND;
    }
    return real(filename, flags);
}

/* ── deletion interceptors ──────────────────────────────────────────────── */

int rmdir(const char *path) {
    static int (*real)(const char *) = NULL;
    if (!real) real = dlsym(RTLD_NEXT, "rmdir");
    fprintf(stderr, "[DLA-PROTECT] rmdir('%s') MTKNN_ADAPTER_DLA_DIR='%s'\n",
            path ? path : "(null)", getenv("MTKNN_ADAPTER_DLA_DIR") ?: "(not set)");
    int ret = real(path);
    protect_after_deletion(path);
    return ret;
}

int unlink(const char *path) {
    static int (*real)(const char *) = NULL;
    if (!real) real = dlsym(RTLD_NEXT, "unlink");
    int ret = real(path);
    protect_after_deletion(path);
    return ret;
}

int unlinkat(int dirfd, const char *path, int flags) {
    static int (*real)(int, const char *, int) = NULL;
    if (!real) real = dlsym(RTLD_NEXT, "unlinkat");
    int ret = real(dirfd, path, flags);
    /* path is relative to dirfd — can't easily compute absolute, so just
     * check if the DLA dir disappeared after every unlinkat. */
    protect_after_deletion(NULL);
    return ret;
}

int remove(const char *path) {
    static int (*real)(const char *) = NULL;
    if (!real) real = dlsym(RTLD_NEXT, "remove");
    int ret = real(path);
    protect_after_deletion(path);
    return ret;
}

__attribute__((constructor))
static void dla_protect_init(void) {
    fprintf(stderr, "[DLA-PROTECT] Loaded; MTKNN_ADAPTER_DLA_DIR=%s\n",
            getenv("MTKNN_ADAPTER_DLA_DIR") ?: "(not set)");
}
