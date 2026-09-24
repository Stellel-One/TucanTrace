@echo off
REM ============================================================
REM  TucanTrace - Visor UML en vivo con el PROTOTIPO INTERACTIVO
REM
REM  Abre dos pestanas en el navegador:
REM    - Visor UML  (el diagrama se ilumina en vivo)
REM    - Terminal   (la salida del programa)
REM
REM  Escribi en ESTA ventana de consola: el programa lee tu teclado
REM  y su salida se refleja en el navegador.
REM ============================================================
setlocal

if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"

cd /d "%~dp0.."
set "TUCANTRACE=%CD%"
set "TUCANGO=%~dp0..\..\PROYECTO LOGICA II"

REM --- Compilar TucanTrace si hace falta ---
if not exist "build\classes\tucantrace\Main.class" (
  echo Compilando TucanTrace...
  if not exist "build\classes" mkdir "build\classes"
  if exist "%TEMP%\tt-src.txt" del "%TEMP%\tt-src.txt"
  for /f "delims=" %%f in ('dir /b /s "src\*.java"') do echo "%%f" >> "%TEMP%\tt-src.txt"
  "%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -cp "lib\*" -d "build\classes" @"%TEMP%\tt-src.txt"
)

REM --- Compilar TucanGo (proyecto principal) si hace falta ---
if not exist "%TUCANGO%\build\co\edu\uniamazonia\logica2\PrototipoInteractivo.class" (
  echo Compilando TucanGo...
  if not exist "%TUCANGO%\build" mkdir "%TUCANGO%\build"
  if exist "%TEMP%\tt-tg.txt" del "%TEMP%\tt-tg.txt"
  for /f "delims=" %%f in ('dir /b /s "%TUCANGO%\src\*.java"') do echo "%%f" >> "%TEMP%\tt-tg.txt"
  "%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -d "%TUCANGO%\build" @"%TEMP%\tt-tg.txt"
)

echo ============================================================
echo   TucanTrace + Prototipo Interactivo de TucanGo
echo.
echo   Visor UML : http://127.0.0.1:8077/
echo   Terminal  : http://127.0.0.1:8077/terminal
echo.
echo   Escribi en ESTA ventana. Mira el navegador.
echo ============================================================
echo.

"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -cp "build\classes;lib\*" ^
  tucantrace.Main --live --delay 0 --http-port 8077 --port 5005 ^
  --exec co.edu.uniamazonia.logica2.PrototipoInteractivo ^
  --exec-cp "%TUCANGO%\build" "%TUCANGO%\src"

echo.
echo (TucanTrace finalizo. Presiona una tecla para cerrar.)
pause >nul
