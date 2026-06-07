@echo off
setlocal

set OUTFILE=diff.txt

echo ==================== GIT STATUS ==================== > %OUTFILE%
git status >> %OUTFILE%

echo. >> %OUTFILE%
echo ==================== GIT DIFF ====================== >> %OUTFILE%
git diff >> %OUTFILE%

echo. >> %OUTFILE%
echo ==================== GIT DIFF --STAGED ============= >> %OUTFILE%
git diff --staged >> %OUTFILE%

echo. >> %OUTFILE%
echo ==================== GIT DIFF STAT ================= >> %OUTFILE%
git diff --stat >> %OUTFILE%

echo. >> %OUTFILE%
echo ==================== MODIFIED FILES ================ >> %OUTFILE%
git diff --name-only >> %OUTFILE%

echo. >> %OUTFILE%
echo ==================== UNTRACKED FILES =============== >> %OUTFILE%
git ls-files --others --exclude-standard >> %OUTFILE%

echo.
echo Report generato: %OUTFILE%
REM pause