param(
    [string]$IdePath = 'D:\PyCharm'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = $PSScriptRoot
$baseVersion = '1.0.5'
$version = '1.0.6'
$baseZip = Join-Path $projectRoot "project-tree-notes-$baseVersion.zip"
$outputZip = Join-Path $projectRoot "project-tree-notes-$version.zip"
$javaHome = Join-Path $IdePath 'jbr\bin'
$javac = Join-Path $javaHome 'javac.exe'

if (-not (Test-Path -LiteralPath $baseZip -PathType Leaf)) {
    throw "缺少基线安装包：$baseZip"
}
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) {
    throw "未找到 PyCharm 自带的 javac：$javac"
}

$buildRoot = Join-Path $projectRoot 'build\1.0.6'
$resolvedProjectRoot = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\')
$resolvedBuildRoot = [IO.Path]::GetFullPath($buildRoot)
if (-not $resolvedBuildRoot.StartsWith($resolvedProjectRoot + '\build\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "拒绝使用项目 build 目录之外的构建路径：$resolvedBuildRoot"
}
if (Test-Path -LiteralPath $resolvedBuildRoot) {
    Remove-Item -LiteralPath $resolvedBuildRoot -Recurse -Force
}

$packageDir = Join-Path $resolvedBuildRoot 'package'
$jarDir = Join-Path $resolvedBuildRoot 'jar'
$classesDir = Join-Path $resolvedBuildRoot 'classes'
$testClassesDir = Join-Path $resolvedBuildRoot 'test-classes'
New-Item -ItemType Directory -Path $packageDir, $jarDir, $classesDir, $testClassesDir -Force | Out-Null

Expand-Archive -LiteralPath $baseZip -DestinationPath $packageDir -Force
$baseJar = Join-Path $packageDir "project-tree-notes\lib\project-tree-notes-$baseVersion.jar"
$compileBaseJar = Join-Path $resolvedBuildRoot "project-tree-notes-$baseVersion.jar"
Copy-Item -LiteralPath $baseJar -Destination $compileBaseJar -Force
Expand-Archive -LiteralPath $baseJar -DestinationPath $jarDir -Force

$sourceFiles = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object FullName
$compileClasspath = (Join-Path $IdePath 'lib\*') + ';' + $compileBaseJar
& $javac --release 21 -encoding UTF-8 -classpath $compileClasspath -d $classesDir $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "插件扩展源码编译失败，退出码：$LASTEXITCODE"
}

Copy-Item -LiteralPath (Join-Path $classesDir 'com') -Destination $jarDir -Recurse -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'src\main\resources\META-INF\plugin.xml') -Destination (Join-Path $jarDir 'META-INF\plugin.xml') -Force

$jarZip = Join-Path $resolvedBuildRoot "project-tree-notes-$version.zip"
$newJar = Join-Path $resolvedBuildRoot "project-tree-notes-$version.jar"
Compress-Archive -Path (Join-Path $jarDir '*') -DestinationPath $jarZip -CompressionLevel Optimal -Force
Move-Item -LiteralPath $jarZip -Destination $newJar -Force

$pluginLibDir = Join-Path $packageDir 'project-tree-notes\lib'
Remove-Item -LiteralPath $baseJar -Force
Copy-Item -LiteralPath $newJar -Destination (Join-Path $pluginLibDir "project-tree-notes-$version.jar") -Force

$packageZip = Join-Path $resolvedBuildRoot "package-$version.zip"
Compress-Archive -Path (Join-Path $packageDir '*') -DestinationPath $packageZip -CompressionLevel Optimal -Force
Copy-Item -LiteralPath $packageZip -Destination $outputZip -Force

$testSource = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tests') -Filter '*.java' | ForEach-Object FullName
$testClasspath = $classesDir + ';' + $compileBaseJar + ';' + (Join-Path $packageDir 'project-tree-notes\lib\*')
& $javac --release 21 -encoding UTF-8 -classpath $testClasspath -d $testClassesDir $testSource
if ($LASTEXITCODE -ne 0) {
    throw "核心逻辑测试源码编译失败，退出码：$LASTEXITCODE"
}
& (Join-Path $javaHome 'java.exe') -classpath ($testClassesDir + ';' + $testClasspath) ClearAllNotesTest
if ($LASTEXITCODE -ne 0) {
    throw "核心逻辑测试失败，退出码：$LASTEXITCODE"
}

& (Join-Path $javaHome 'java.exe') -classpath ($testClassesDir + ';' + $testClasspath) com.projecttreenotes.plugin.ai.AiHandshakeRetryTest
if ($LASTEXITCODE -ne 0) { throw 'AI 网络回归测试失败' }
Write-Host "已生成：$outputZip"


