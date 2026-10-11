@echo off
rem Kookaburra Studio launcher. Data defaults to .\data next to this script (see kookaburra.properties).
rem   studio.cmd              open the default data directory
rem   studio.cmd --demo       open an in-memory demo database
rem   studio.cmd --data=PATH  open another database directory
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xmx1g
java %JAVA_OPTS% -jar "%~dp0kookaburra-face.jar" %*
