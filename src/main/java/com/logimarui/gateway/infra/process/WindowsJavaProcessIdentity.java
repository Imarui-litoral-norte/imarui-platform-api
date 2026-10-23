package com.logimarui.gateway.infra.process;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Windows JDK17 does not expose child command-line arguments through ProcessHandle.Info. */
final class WindowsJavaProcessIdentity {
    record Snapshot(String executable,String commandLine,Instant created) {}

    static Snapshot read(long pid) throws IOException,InterruptedException {
        if(pid<=0)throw new IllegalArgumentException("Invalid process id");
        String script="[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+pid+"';"
                +"if($null -ne $p){[pscustomobject]@{executable=$p.ExecutablePath;commandLine=$p.CommandLine;"
                +"created=$p.CreationDate.ToUniversalTime().ToString('o')}|ConvertTo-Json -Compress}";
        var process=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",script)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(5,TimeUnit.SECONDS)){
            process.destroyForcibly();throw new IOException("Process identity lookup timed out");
        }
        if(process.exitValue()!=0)throw new IOException("Process identity lookup failed");
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        if(output.isEmpty())return null;
        try {
            var data=JsonMapper.builder().build().readTree(output);
            return new Snapshot(data.path("executable").asText(),data.path("commandLine").asText(),Instant.parse(data.path("created").asText()));
        } catch(RuntimeException invalid){throw new IOException("Process identity is not available");}
    }

    static boolean invokesArtifact(String commandLine,Path artifact) {
        if(commandLine==null)return false;
        String expected=Pattern.quote(artifact.toAbsolutePath().normalize().toString());
        return Pattern.compile("(?:^|\\s)-jar\\s+(?:\""+expected+"\"|"+expected+")(?=\\s|$)",Pattern.CASE_INSENSITIVE)
                .matcher(commandLine).find();
    }
}
