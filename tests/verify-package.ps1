param(
    [string]$PackagePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'project-tree-notes-1.0.6.zip')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$baseZip = Join-Path $projectRoot 'project-tree-notes-1.0.5.zip'
$tempBase = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build')).TrimEnd('\')
$verifyRoot = Join-Path $tempBase ("project-tree-notes-verify-" + [Guid]::NewGuid().ToString('N'))
$resolvedVerifyRoot = [IO.Path]::GetFullPath($verifyRoot)
if (-not $resolvedVerifyRoot.StartsWith($tempBase + '\project-tree-notes-verify-', [StringComparison]::OrdinalIgnoreCase)) {
    throw "拒绝使用临时验收目录之外的路径：$resolvedVerifyRoot"
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

try {
    $baseDir = Join-Path $resolvedVerifyRoot 'base'
    $newDir = Join-Path $resolvedVerifyRoot 'new'
    $baseJarDir = Join-Path $resolvedVerifyRoot 'base-jar'
    $newJarDir = Join-Path $resolvedVerifyRoot 'new-jar'
    New-Item -ItemType Directory -Path $baseDir, $newDir, $baseJarDir, $newJarDir -Force | Out-Null

    Assert-True (Test-Path -LiteralPath $PackagePath -PathType Leaf) "安装包不存在：$PackagePath"
    Expand-Archive -LiteralPath $baseZip -DestinationPath $baseDir -Force
    Expand-Archive -LiteralPath $PackagePath -DestinationPath $newDir -Force

    $baseLib = Join-Path $baseDir 'project-tree-notes\lib'
    $newLib = Join-Path $newDir 'project-tree-notes\lib'
    $baseJar = Join-Path $baseLib 'project-tree-notes-1.0.5.jar'
    $newJar = Join-Path $newLib 'project-tree-notes-1.0.6.jar'
    Assert-True (Test-Path -LiteralPath $newJar -PathType Leaf) '缺少 1.0.6 主 JAR'

    foreach ($dependency in 'gson-2.13.2.jar', 'error_prone_annotations-2.41.0.jar') {
        $baseHash = (Get-FileHash -LiteralPath (Join-Path $baseLib $dependency) -Algorithm SHA256).Hash
        $newHash = (Get-FileHash -LiteralPath (Join-Path $newLib $dependency) -Algorithm SHA256).Hash
        Assert-True ($baseHash -eq $newHash) "现有依赖被改写：$dependency"
    }

    Expand-Archive -LiteralPath $baseJar -DestinationPath $baseJarDir -Force
    Expand-Archive -LiteralPath $newJar -DestinationPath $newJarDir -Force
    $manifest = [xml](Get-Content -Raw (Join-Path $newJarDir 'META-INF\plugin.xml'))
    Assert-True ($manifest.'idea-plugin'.version -eq '1.0.6') 'plugin.xml 版本不是 1.0.6'
    Assert-True ($manifest.'idea-plugin'.'idea-version'.'since-build' -eq '261') 'since-build 发生变化'
    Assert-True ($manifest.'idea-plugin'.'idea-version'.'until-build' -eq '261.*') 'until-build 发生变化'

    $action = @($manifest.'idea-plugin'.actions.group.action) | Where-Object id -eq 'ProjectChineseNotes.ClearAllNotes'
    Assert-True ($action.Count -eq 1) '清除全部说明菜单 Action 未正确注册'
    Assert-True ($action.class -eq 'com.projecttreenotes.plugin.action.ClearAllNotesAction') '清除 Action 类名不正确'
    foreach ($existingActionId in @(
        'ProjectChineseNotes.EditNote',
        'ProjectChineseNotes.AiGenerate',
        'ProjectChineseNotes.UndoAiGeneration',
        'ProjectChineseNotes.OpenTable',
        'ProjectChineseNotes.OpenAiSettings'
    )) {
        $existingAction = @($manifest.'idea-plugin'.actions.group.action) | Where-Object id -eq $existingActionId
        Assert-True ($existingAction.Count -eq 1) "原有菜单 Action 未保留：$existingActionId"
    }
    Assert-True (Test-Path -LiteralPath (Join-Path $newJarDir 'com\projecttreenotes\plugin\action\ClearAllNotesAction.class')) '缺少清除 Action 字节码'
    Assert-True (Test-Path -LiteralPath (Join-Path $newJarDir 'com\projecttreenotes\core\ClearAllNotes.class')) '缺少清除核心逻辑字节码'

    $baseClasses = Get-ChildItem -LiteralPath (Join-Path $baseJarDir 'com') -Filter '*.class' -Recurse
    foreach ($baseClass in $baseClasses) {
        $relative = [IO.Path]::GetRelativePath($baseJarDir, $baseClass.FullName)
        $newClass = Join-Path $newJarDir $relative
        Assert-True (Test-Path -LiteralPath $newClass -PathType Leaf) "原有类缺失：$relative"
        $baseHash = (Get-FileHash -LiteralPath $baseClass.FullName -Algorithm SHA256).Hash
        $newHash = (Get-FileHash -LiteralPath $newClass -Algorithm SHA256).Hash
                $changedClasses = @(
            'com\projecttreenotes\plugin\ai\AiClient.class',
            'com\projecttreenotes\plugin\action\AiGenerateNotesAction.class',
            'com\projecttreenotes\plugin\action\AiGenerateNotesAction$1.class',
            'com\projecttreenotes\plugin\action\AiGenerateNotesAction$SettingsSnapshot.class'
        )
        if ($relative -notin $changedClasses) {
            Assert-True ($baseHash -eq $newHash) "无关类被改写：$relative"
        }
    }

    $baseResources = Get-ChildItem -LiteralPath $baseJarDir -File -Recurse | Where-Object {
        $_.Extension -ne '.class' -and [IO.Path]::GetRelativePath($baseJarDir, $_.FullName) -ne 'META-INF\plugin.xml'
    }
    foreach ($baseResource in $baseResources) {
        $relative = [IO.Path]::GetRelativePath($baseJarDir, $baseResource.FullName)
        $newResource = Join-Path $newJarDir $relative
        Assert-True (Test-Path -LiteralPath $newResource -PathType Leaf) "原有资源缺失：$relative"
        $baseHash = (Get-FileHash -LiteralPath $baseResource.FullName -Algorithm SHA256).Hash
        $newHash = (Get-FileHash -LiteralPath $newResource -Algorithm SHA256).Hash
        Assert-True ($baseHash -eq $newHash) "原有资源被改写：$relative"
    }

    Write-Host 'verify-package: PASS'
}
finally {
    if (Test-Path -LiteralPath $resolvedVerifyRoot) {
        Remove-Item -LiteralPath $resolvedVerifyRoot -Recurse -Force
    }
}

