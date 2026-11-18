@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"
set "VENV=%~dp0.venv"

if exist "%VENV%\Scripts\python.exe" goto :install
echo [한글자막 플레이어] 처음 실행입니다. 필요한 프로그램을 설치합니다 (몇 분 걸릴 수 있음)...
where py > nul 2> nul
if errorlevel 1 goto :use_python
py -3 -m venv "%VENV%"
if exist "%VENV%\Scripts\python.exe" goto :install
:use_python
python -m venv "%VENV%"
if not exist "%VENV%\Scripts\python.exe" goto :nopython

:install
if exist "%VENV%\.installed" goto :run
"%VENV%\Scripts\python.exe" -m pip install --upgrade pip
"%VENV%\Scripts\python.exe" -m pip install -r "%~dp0requirements.txt"
if errorlevel 1 goto :fail
echo ok> "%VENV%\.installed"

:run
start "" "%VENV%\Scripts\pythonw.exe" "%~dp0main.py" %*
exit /b 0

:nopython
echo.
echo Python 3.10 이상이 필요합니다.
echo https://www.python.org/downloads/ 에서 설치할 때 "Add python.exe to PATH"를 꼭 체크하세요.
pause
exit /b 1

:fail
echo.
echo 설치 중 오류가 발생했습니다. 위 메시지를 확인한 뒤 다시 실행하세요.
rmdir /s /q "%VENV%" 2> nul
pause
exit /b 1
