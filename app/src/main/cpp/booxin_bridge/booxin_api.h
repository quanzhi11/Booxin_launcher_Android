#ifndef BOOXIN_API_H
#define BOOXIN_API_H

#ifdef __cplusplus
extern "C" {
#endif

/* Primary Booxin bridge ABI (LWJGL / launcher resolve these names). */
int booxinInit(void);
int booxinInitOpenGL(void);
void *booxinCreateContext(void *contextSrc);
void *booxinGetCurrentContext(void);
void booxinMakeCurrent(void *window);
void booxinSwapBuffers(void);
void booxinSwapInterval(int interval);
void booxinSetWindowHint(int hint, int value);
void booxinTerminate(void);
void booxinStartPumping(void);
void booxinStopPumping(void);
void booxinPumpEvents(void *window);
void booxinSetInjectorCallback(void *cb);
void booxinSetHitResultType(int type);

/* Legacy symbol names — defined as aliases in booxin_api_aliases.c */
int pojavInit(void);
int pojavInitOpenGL(void);
void *pojavCreateContext(void *contextSrc);
void *pojavGetCurrentContext(void);
void pojavMakeCurrent(void *window);
void pojavSwapBuffers(void);
void pojavSwapInterval(int interval);
void pojavSetWindowHint(int hint, int value);
void pojavTerminate(void);
void pojavStartPumping(void);
void pojavStopPumping(void);
void pojavPumpEvents(void *window);
void pojavSetInjectorCallback(void *cb);
void pojavSetHitResultType(int type);

#ifdef __cplusplus
}
#endif

#endif
