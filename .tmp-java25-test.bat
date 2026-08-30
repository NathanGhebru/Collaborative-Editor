@echo off
set "JAVA_HOME=C:\Users\natha\Downloads\College\Projects\Collaborative-Editor\.tools\jdk-25\jdk-25.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "C:\Users\natha\Downloads\College\Projects\Collaborative-Editor"
"C:\Users\natha\Downloads\College\Projects\Collaborative-Editor\.tools\maven\maven-3.9.15\bin\mvn.cmd" --batch-mode --file backend/pom.xml clean test -q
