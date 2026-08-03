#include "booxin_environ.h"

#include <stdlib.h>
#include <string.h>

booxin_environ_t g_booxin_environ;
booxin_environ_t *pojav_environ = &g_booxin_environ;

void booxin_environ_init(void) {
    static int once;
    if (once) return;
    once = 1;
    memset(&g_booxin_environ, 0, sizeof(g_booxin_environ));
    pojav_environ = &g_booxin_environ;
    g_booxin_environ.keyDownBuffer = (jbyte *)calloc(317, 1);
    g_booxin_environ.mouseDownBuffer = (jbyte *)calloc(8, 1);
    g_booxin_environ.isUseStackQueueCall = true;
}
