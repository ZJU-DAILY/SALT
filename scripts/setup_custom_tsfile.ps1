$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$workRoot = Join-Path $repoRoot "third_party\_work"
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null

function Copy-Overlay {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Get-ChildItem -LiteralPath $Source -File -Recurse | ForEach-Object {
        $relative = $_.FullName.Substring($Source.Length).TrimStart('\', '/')
        $target = Join-Path $Destination $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
    }
}

$commit = "38a847ddae3e26030208fbee9bcbc61e98e342f9"
$destination = Join-Path $workRoot "tsfile"
if (Test-Path -LiteralPath $destination) {
    throw "Destination already exists: $destination"
}

git clone https://github.com/apache/tsfile.git $destination
git -C $destination checkout $commit
Copy-Overlay -Source (Join-Path $repoRoot "third_party\tsfile\overlay") -Destination $destination
mvn -q -f (Join-Path $destination "pom.xml") -pl java/tsfile -am -DskipTests install
Write-Host "Prepared and installed customized TsFile from $destination"
