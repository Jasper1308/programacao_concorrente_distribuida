@echo off
setlocal

where java >nul 2>nul
if errorlevel 1 (
  echo ERRO: comando java nao encontrado.
  echo Instale o JDK 8 ou superior e reabra o Prompt de Comando.
  pause
  exit /b 1
)

where javac >nul 2>nul
if errorlevel 1 (
  echo ERRO: comando javac nao encontrado.
  echo Voce provavelmente instalou apenas o JRE. Instale o JDK 8.
  echo Depois feche e abra novamente o terminal.
  pause
  exit /b 1
)

echo Java encontrado:
java -version
echo.
echo Compilador encontrado:
javac -version
echo.

if not exist out mkdir out
del /q out\*.class >nul 2>nul

javac -encoding UTF-8 -source 1.8 -target 1.8 -d out src\*.java
if errorlevel 1 (
  echo.
  echo ERRO NA COMPILACAO
  pause
  exit /b 1
)

echo.
echo Compilacao Java 8 concluida com sucesso.
pause
endlocal
