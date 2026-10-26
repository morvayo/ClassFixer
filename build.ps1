$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force -Path build/classes | Out-Null
    javac --release 17 -encoding UTF-8 -d build/classes src/classfixer/Main.java
    if ($LASTEXITCODE -ne 0) { throw 'compilation failed' }
    jar --create --file build/ClassFixer.jar --main-class classfixer.Main -C build/classes .
    if ($LASTEXITCODE -ne 0) { throw 'packaging failed' }
    Write-Output 'build -> ok'
} finally {
    Pop-Location
}
