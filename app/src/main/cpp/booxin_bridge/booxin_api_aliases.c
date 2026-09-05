#include "booxin_api.h"

/*
 * Thin wrappers keeping legacy symbol names for any leftover dlsym("pojav*").
 * Primary API is booxin*.
 */
int pojavInit(void) { return booxinInit(); }
int pojavInitOpenGL(void) { return booxinInitOpenGL(); }
void *pojavCreateContext(void *contextSrc) { return booxinCreateContext(contextSrc); }
void *pojavGetCurrentContext(void) { return booxinGetCurrentContext(); }
void pojavMakeCurrent(void *window) { booxinMakeCurrent(window); }
void pojavSwapBuffers(void) { booxinSwapBuffers(); }
void pojavSwapInterval(int interval) { booxinSwapInterval(interval); }
void pojavSetWindowHint(int hint, int value) { booxinSetWindowHint(hint, value); }
void pojavTerminate(void) { booxinTerminate(); }
void pojavStartPumping(void) { booxinStartPumping(); }
void pojavStopPumping(void) { booxinStopPumping(); }
void pojavPumpEvents(void *window) { booxinPumpEvents(window); }
void pojavSetInjectorCallback(void *cb) { booxinSetInjectorCallback(cb); }
void pojavSetHitResultType(int type) { booxinSetHitResultType(type); }
