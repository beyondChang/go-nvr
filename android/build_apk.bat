@echo off
cd /d D:\system\desktop\ai\go-nvr\android
set "JAVA_HOME=D:\soft\android\data\.gradle\jdks\jetbrains_s_r_o_-17-amd64-windows.2"
set "ANDROID_HOME=D:\soft\android\sdk"
echo JAVA_HOME=%JAVA_HOME%
echo ANDROID_HOME=%ANDROID_HOME%
echo Gradle version:
"%JAVA_HOME%\bin\java.exe" -version 2>&1
call "D:\soft\android\data\.gradle\wrapper\dists\gradle-8.13-all\5vnaui2o3j0xnpp92ig93m6i1\gradle-8.13\bin\gradle.bat" %*
