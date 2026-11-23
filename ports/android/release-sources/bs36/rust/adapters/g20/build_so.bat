@REM SPDX-FileCopyrightText: 2026 amurcanov
@REM SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

@echo off
setlocal
call "%~dp0rust-client\build_so.bat" %*
exit /b %errorlevel%
