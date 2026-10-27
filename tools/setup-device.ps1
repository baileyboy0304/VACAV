[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('standard','overlay')][string]$Platform,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$Serial,
    [Parameter(Mandatory)][string]$ApkPath,
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$VisionPackage = 'com.mph1970.vacavision'
function Invoke-DeviceAdb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $result = & adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -match 'SecurityException|Failure \[|Permission Denial') {
        throw "ADB failed: $($result -join ' ')"
    }
    $result
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw 'APK not found' }
if ($Serial.Contains(':')) {
    & adb connect $Serial
    if ($LASTEXITCODE -ne 0) { throw 'Could not connect to network ADB' }
}
Invoke-DeviceAdb get-state
$CrownPresent = $false
if ($Platform -eq 'standard') {
    $probe = & adb -s $Serial shell test -f /data/local/crown-assist/firewall.sh 2>&1
    $CrownPresent = $LASTEXITCODE -eq 0
}
try {
    if ($CrownPresent) {
        Invoke-DeviceAdb shell touch /data/local/crown-assist/policy-update.lock
        Invoke-DeviceAdb shell sh /data/local/crown-assist/firewall.sh close
    }
    Invoke-DeviceAdb install -r (Resolve-Path -LiteralPath $ApkPath).Path
    if ($Platform -eq 'standard') {
        Invoke-DeviceAdb shell appops set $VisionPackage WRITE_SETTINGS allow
        $grant = Invoke-DeviceAdb shell appops get $VisionPackage WRITE_SETTINGS
        if (($grant -join ' ') -notmatch 'WRITE_SETTINGS:\s*allow') { throw 'WRITE_SETTINGS grant was not confirmed' }
        if ($CrownPresent) {
            $policyLines = @(Invoke-DeviceAdb shell cat /data/local/crown-assist/apps.tsv)
            if ($policyLines[0].Trim() -ne "Name`tPackage ID`tAllow internet access`tMode") { throw 'Unexpected Crown whitelist format' }
            $policyLines = @($policyLines | Where-Object { ($_ -split "`t")[1] -ne $VisionPackage })
            $policyLines += "VACA_Vision`t$VisionPackage`tYES`tWEB"
            $policyFile = [System.IO.Path]::GetTempFileName()
            try {
                [System.IO.File]::WriteAllText($policyFile, ($policyLines -join "`n") + "`n", [System.Text.UTF8Encoding]::new($false))
                Invoke-DeviceAdb push $policyFile /data/local/crown-assist/apps-vision.new
                Invoke-DeviceAdb shell chmod 600 /data/local/crown-assist/apps-vision.new
                Invoke-DeviceAdb shell chown 0:0 /data/local/crown-assist/apps-vision.new
                Invoke-DeviceAdb -Arguments @('shell', 'cp', '-p', '/data/local/crown-assist/apps.tsv', '/data/local/crown-assist/apps-before-vision-install.tsv')
                Invoke-DeviceAdb shell mv /data/local/crown-assist/apps-vision.new /data/local/crown-assist/apps.tsv
            } finally { Remove-Item -LiteralPath $policyFile -ErrorAction SilentlyContinue }
        }
    } else {
        Invoke-DeviceAdb shell appops set $VisionPackage SYSTEM_ALERT_WINDOW allow
        $grant = Invoke-DeviceAdb shell appops get $VisionPackage SYSTEM_ALERT_WINDOW
        if (($grant -join ' ') -notmatch 'SYSTEM_ALERT_WINDOW:\s*allow') { throw 'Overlay permission was not confirmed' }
        $api = [int]((Invoke-DeviceAdb shell getprop ro.build.version.sdk) -join '').Trim()
        if ($api -ge 31) {
            Invoke-DeviceAdb shell pm grant $VisionPackage android.permission.BLUETOOTH_SCAN
            Invoke-DeviceAdb shell pm grant $VisionPackage android.permission.BLUETOOTH_CONNECT
        } else {
            Invoke-DeviceAdb shell pm grant $VisionPackage android.permission.ACCESS_FINE_LOCATION
        }
    }
} finally {
    if ($CrownPresent) { Invoke-DeviceAdb shell rm -f /data/local/crown-assist/policy-update.lock }
}
if (-not $NoLaunch) {
    Invoke-DeviceAdb shell am start -n "$VisionPackage/com.msp1974.vacompanion.MainActivity" --es vacav_mode $Platform
}
Write-Host "VACA_Vision $Platform installed; device permissions verified."
