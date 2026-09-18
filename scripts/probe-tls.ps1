param([Parameter(Mandatory=$true)][string]$Address, [Parameter(Mandatory=$true)][string]$Fingerprint)
$ErrorActionPreference = 'Stop'
if ($Fingerprint -notmatch '^[0-9a-f]{64}$') { exit 1 }
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
public static class RythmoTlsProbe {
    public static bool Check(string address, string fingerprint) {
        using (var tcp = new TcpClient()) {
            var pending = tcp.ConnectAsync(address, 8765);
            if (!pending.Wait(3000)) return false;
            tcp.ReceiveTimeout = 3000; tcp.SendTimeout = 3000;
            using (var tls = new SslStream(tcp.GetStream(), false, (sender, cert, chain, errors) => {
                if (cert == null) return false;
                using (var sha = SHA256.Create()) {
                    var actual = BitConverter.ToString(sha.ComputeHash(cert.GetRawCertData())).Replace("-", "").ToLowerInvariant();
                    var x509 = new X509Certificate2(cert);
                    return actual == fingerprint && DateTime.Now >= x509.NotBefore && DateTime.Now <= x509.NotAfter;
                }
            })) {
                tls.ReadTimeout = 3000; tls.WriteTimeout = 3000;
                tls.AuthenticateAsClient("Rythmo local", null, SslProtocols.Tls12, false);
                var request = Encoding.ASCII.GetBytes("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                tls.Write(request, 0, request.Length);
                using (var reader = new StreamReader(tls)) {
                    var buffer = new char[4096];
                    var count = 0;
                    while (count < buffer.Length) { var n = reader.Read(buffer, count, buffer.Length - count); if (n == 0) break; count += n; }
                    return new string(buffer, 0, count).Contains("\"name\":\"Rythmo\"");
                }
            }
        }
    }
}
'@
try { if ([RythmoTlsProbe]::Check($Address, $Fingerprint)) { exit 0 } } catch { }
exit 1
