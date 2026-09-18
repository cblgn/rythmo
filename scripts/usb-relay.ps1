param([Parameter(Mandatory=$true)][string]$Adb, [Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference = 'Stop'
# WSL can expose its server to Windows on IPv6 loopback only. ADB reverse uses IPv4.
Add-Type -TypeDefinition @'
using System;
using System.Net.Sockets;
using System.Threading.Tasks;
public static class RythmoUsbRelay {
    public static async Task Bridge(TcpClient phone) {
        using (phone)
        using (var server = new TcpClient(AddressFamily.InterNetworkV6)) {
            try {
                await server.ConnectAsync(System.Net.IPAddress.IPv6Loopback, 8765);
                var left = phone.GetStream();
                var right = server.GetStream();
                await Task.WhenAny(left.CopyToAsync(right), right.CopyToAsync(left));
            } catch (Exception) { /* Closing both sockets reports the error to the client. */ }
        }
    }
}
'@
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try {
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    & $Adb -s $Serial reverse tcp:8765 "tcp:$port"
    if ($LASTEXITCODE -ne 0) { throw 'Impossible de configurer le tunnel ADB.' }
    Write-Host '[phone] Relais USB actif. Adresse Rythmo : http://127.0.0.1:8765'
    Write-Host '[phone] Gardez ce terminal ouvert pendant les synchronisations. Ctrl+C pour fermer le relais.'
    while ($true) {
        if ($listener.Pending()) { $null = [RythmoUsbRelay]::Bridge($listener.AcceptTcpClient()) }
        else { Start-Sleep -Milliseconds 100 }
    }
} finally {
    $listener.Stop()
    & $Adb -s $Serial reverse --remove tcp:8765
}
