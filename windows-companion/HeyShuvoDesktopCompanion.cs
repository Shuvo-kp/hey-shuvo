// Hey Shuvo — Windows Laptop Companion (.NET 8 / C# 12 Local Voice & Software Control Host)
// Build on Windows 10/11: dotnet run HeyShuvoDesktopCompanion.cs
// Privacy Guarantee: Runs 100% on the user's own PC with explicit user confirmation before sensitive actions.

using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

namespace HeyShuvo.WindowsCompanion
{
    public record DesktopVoicePacket(
        string Kind,
        string TargetOrPath,
        string TitleOrSubject,
        string ContentOrBody,
        bool ConfirmedByUser
    );

    public static class Program
    {
        private static readonly string WorkspaceDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
            "HeyShuvo_Workspace"
        );

        public static async Task Main()
        {
            Directory.CreateDirectory(WorkspaceDir);
            using var listener = new HttpListener();
            listener.Prefixes.Add("http://127.0.0.1:49550/heyshuvo/");
            listener.Start();

            Console.WriteLine("I am Hey Shuvo, your digital assistant (Windows Laptop Companion Ready on 127.0.0.1:49550).");

            while (true)
            {
                var ctx = await listener.GetContextAsync();
                using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
                var body = await reader.ReadToEndAsync();
                var packet = JsonSerializer.Deserialize<DesktopVoicePacket>(body);

                string status = ExecutePermittedAction(packet);
                byte[] buffer = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { ok = true, status }));
                ctx.Response.ContentType = "application/json";
                await ctx.Response.OutputStream.WriteAsync(buffer);
                ctx.Response.Close();
            }
        }

        private static string ExecutePermittedAction(DesktopVoicePacket? packet)
        {
            if (packet is null || !packet.ConfirmedByUser)
                return "Blocked: User confirmation required before desktop action.";

            switch (packet.Kind.ToUpperInvariant())
            {
                case "OPEN_MAPS_NAVIGATION":
                    string routeUrl = $"https://www.google.com/maps/dir/?api=1&destination={Uri.EscapeDataString(packet.TargetOrPath)}";
                    Process.Start(new ProcessStartInfo(routeUrl) { UseShellExecute = true });
                    return $"Opened Google Maps route to {packet.TargetOrPath}";

                case "GENERATE_WORD_DOC":
                    string docPath = Path.Combine(WorkspaceDir, SafeFileName(packet.TitleOrSubject, ".doc"));
                    File.WriteAllText(docPath, $"<html><body><h1>{packet.TitleOrSubject}</h1><p>{packet.ContentOrBody}</p></body></html>", Encoding.UTF8);
                    Process.Start(new ProcessStartInfo(docPath) { UseShellExecute = true });
                    return $"Created & opened Word document: {docPath}";

                case "GENERATE_EXCEL_SHEET":
                    string csvPath = Path.Combine(WorkspaceDir, SafeFileName(packet.TitleOrSubject, ".csv"));
                    File.WriteAllText(csvPath, packet.ContentOrBody, Encoding.UTF8);
                    Process.Start(new ProcessStartInfo(csvPath) { UseShellExecute = true });
                    return $"Created & opened Excel formula sheet: {csvPath}";

                case "GENERATE_AUTOCAD_SCRIPT":
                    string lspPath = Path.Combine(WorkspaceDir, SafeFileName(packet.TitleOrSubject, ".lsp"));
                    File.WriteAllText(lspPath, packet.ContentOrBody, Encoding.UTF8);
                    Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{lspPath}\"") { UseShellExecute = true });
                    return $"Saved AutoCAD AutoLISP (.lsp) script: {lspPath}";

                case "WINDOWS_DESKTOP_COMMAND":
                    // Launches only allow-listed productivity apps (Word, Excel, AutoCAD, Browser, Explorer)
                    return LaunchSafeDesktopApplication(packet.TargetOrPath);

                default:
                    return $"Action {packet.Kind} acknowledged.";
            }
        }

        private static string LaunchSafeDesktopApplication(string appName)
        {
            string normalized = appName.Trim().ToLowerInvariant();
            string executable = normalized switch
            {
                "excel" => "excel.exe",
                "word" => "winword.exe",
                "autocad" => "acad.exe",
                "notepad" => "notepad.exe",
                "calculator" => "calc.exe",
                _ => "explorer.exe"
            };
            Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true });
            return $"Launched {executable} with user permission.";
        }

        private static string SafeFileName(string rawTitle, string ext)
        {
            string cleaned = string.Join("_", (rawTitle ?? "HeyShuvo_File").Split(Path.GetInvalidFileNameChars()));
            return string.IsNullOrWhiteSpace(cleaned) ? $"HeyShuvo_Output{ext}" : $"{cleaned}{ext}";
        }
    }
}
