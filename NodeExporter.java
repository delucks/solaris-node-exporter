/* NodeExporter - a prometheus node_exporter compatible service for Solaris 10
 * Targets Java 5; any later releases should run fine
 * Version 0.1.0: Initial release
 * Copyright (C) 2026, Jamie Luck (delucks)
 *
 * This requires the following command-line programs to be in PATH:
 * arp      (typically /usr/sbin/arp)
 * kstat    (typically /usr/bin/kstat)
 * pagesize (typically /usr/bin/pagesize)
 * uptime   (typically /usr/bin/uptime)
 * vmstat   (typically /usr/bin/vmstat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/*
 * KstatException is an exception type used to return parsing errors from the KstatEntry constructor
 */
class KstatException extends Exception {
    public KstatException(String m) {
        super(m);
    }
}

/*
 * KstatEntry represents a single entry of kernel-level statistics returned by the
 * kstat facility in Solaris. This class is constructed with the output of the `kstat`
 * command-line utility for one module, instance, name, and class combination.
 */
class KstatEntry {
    private String module, instance, name, kstatClass;
    private HashMap<String, String> values;

    public KstatEntry(String kstatStanza) throws KstatException {
        String[] lines = kstatStanza.trim().split("\n");
        // If there's not at least one k/v pair after the header lines it's not going to be useful to parse
        if (lines.length < 3) {
            throw new KstatException("The kstat stanza provided doesn't have enough content");
        }
        String module, instance, name, kstatClass;
        module = instance = name = kstatClass = "";

        // Parse module and instance from the first line
        String[] firstLineSections = lines[0].trim().split("\\s+");
        // module:, instance:, and their respective values need to be present to be valid
        if (firstLineSections.length < 4) {
            throw new KstatException("The first line did not have enough fields: " + lines[0]);
        }
        if (firstLineSections[0].equals("module:")) {
            module = firstLineSections[1];
        }
        if (firstLineSections[2].equals("instance:")) {
            instance = firstLineSections[3];
        }
        if (module.equals("") || instance.equals("")) {
            throw new KstatException("The first line doesn't contain both module and instance metadata: " + lines[0]);
        }

        // Parse name and class from the second line
        String[] secondLineSections = lines[1].trim().split("\\s+");
        if (secondLineSections.length < 4) {
            throw new KstatException("The second line did not have enough fields: " + lines[1]);
        }
        if (secondLineSections[0].equals("name:")) {
            name = secondLineSections[1];
        }
        if (secondLineSections[2].equals("class:")) {
            kstatClass = secondLineSections[3];
        }
        if (name.equals("") || kstatClass.equals("")) {
            throw new KstatException("The second line doesn't contain both name and class metadata: " + lines[1]);
        }

        // Parse key-value pairs from remaining lines
        HashMap<String, String> kstatData = new HashMap<String, String>();
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) {
                continue;
            }
            kstatData.put(parts[0], parts[1]);
        }

        this.module = module;
        this.instance = instance;
        this.name = name;
        this.kstatClass = kstatClass;
        this.values = kstatData;
    }

    public String getOrDefault(String key, String fallback) {
        if (this.values.containsKey(key)) {
            return this.values.get(key);
        } else {
            return fallback;
        }
    }

    public String toString() {
        return String.format("module: %s name: %s instance: %s class: %s values: %s", module, name, instance, kstatClass, values);
    }
}

/*
 * NodeExporter is the main server class of the NodeExporter service, responsible for scraping system
 * information and returning it over HTTP
 */
public class NodeExporter {
    private static final SimpleDateFormat apache = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z");
    private static final String version = "0.1.0";
    private static int pagesize;

    public static void main(String[] args) {
        int port = 9100;
        pagesize = getSystemPageSize();
        if (pagesize < 0) {
            System.err.println("Could not determine page size, exiting");
            System.exit(1);
        }
        // Parse command-line args
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-h") || args[i].equals("--help")) {
                System.out.println("NodeExporter: a prometheus node-exporter compatible service for Solaris 10\n");
                System.out.println("Version " + version);
                System.out.println("Usage Summary");
                System.out.println("  -h/--help:           Show this help text");
                System.out.println("  -v/--version:        Display this build's version of NodeExporter");
                System.out.println("  -p/--port <PORTNUM>: Set the port number to PORTNUM");
                return;
            } else if (args[i].equals("-v") || args[i].equals("--version")) {
                System.out.println(version);
                return;
            } else if (args[i].equals("-p") || args[i].equals("--port")) {
                if (i + 1 < args.length) {
                    try {
                        port = Integer.parseInt(args[++i]);
                        if (port <= 0) {
                            System.err.println("Port must be greater than 0!");
                            System.exit(1);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Cannot parse port number: " + args[i]);
                        System.exit(1);
                    }
                } else {
                    System.err.println("Missing port number for -p/--port argument");
                    System.exit(1);
                }
            }
        }
        // Set up the server
        ServerSocket serverSocket = null;
        Socket clientSocket = null;
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            System.err.println("Error creating socket: " + e.getMessage());
            System.exit(1);
        }
        System.err.println("NodeExporter started on port " + port);
        // Accept connections (single-threaded)
        while (true) {
            try {
                clientSocket = serverSocket.accept();
                handle(clientSocket);
            } catch (IOException e) {
                System.err.println("Error handling client: " + e.getMessage());
            } finally {
                if (clientSocket != null) {
                    try {
                        clientSocket.close();
                    } catch (IOException e) {
                        System.err.println("Error closing socket: " + e.getMessage());
                    }
                }
            }
        }
    }

    public static void handle(Socket socket) throws IOException {
        // Parse the HTTP request headers
        String requestLine, line, method, path, referer, userAgent, client;
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        // NOTE: auto-flushing is enabled-- we should check to make sure this isn't causing weird behavior once running under load
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        // First line of the request should be the method, path, and protocol
        requestLine = in.readLine();
        if (requestLine == null) {
            return;
        }
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 3) {
            return;
        }
        method = parts[0];
        path = parts[1];
        // Read the headers we need if we're on the right path and method
        referer = "-";
        userAgent = "-";
        while ((line = in.readLine()) != null && line.length() > 0) {
            if (line.toLowerCase().startsWith("referer:")) {
                referer = line.substring(8).trim();
            } else if (line.toLowerCase().startsWith("user-agent:")) {
                userAgent = line.substring(11).trim();
            }
        }
        // We don't technically need this for the headers but it's used in the logging statements soooo...
        InetAddress remoteAddr = socket.getInetAddress();
        client = remoteAddr != null ? remoteAddr.getHostAddress() : "-";

        StringBuilder response = null;
        // This server only supports GET /metrics; give a 404 otherwise
        if (!method.equals("GET") || !path.equals("/metrics")) {
            response = new StringBuilder("<html>\r\n<head><title>Resource Not Found</title></head>\r\n<body><p>Not Found. Try <a href=\"/metrics\">/metrics</a></p></body>\r\n</html>\r\n");
            httpRespond(404, out, response);
            apacheLog(client, requestLine, 404, response.length(), referer, userAgent);
            return;
        }

        // Run all system collectors and give a 200 response with the body of the scrapers which functioned
        // A partial response is still a 200 response, but with scraper failure status noted in the response body
        response = runScrapers();
        httpRespond(200, out, response);

        apacheLog(client, requestLine, 200, response.length(), referer, userAgent);
    }

    public static void apacheLog(String client, String requestLine, int statusCode, int length, String referer, String userAgent) {
        // Emit web server log in Apache combined log format to standard output
        String timestamp = apache.format(new Date());
        System.out.printf("%s - - [%s] \"%s\" %d %d \"%s\" \"%s\"%n", client, timestamp, requestLine, statusCode, length, referer, userAgent);
    }

    public static void httpRespond(int status, PrintWriter out, StringBuilder body) {
        if (status == 200) {
            out.print("HTTP/1.1 200 OK\r\n");
        } else if (status == 404) {
            out.print("HTTP/1.1 404 NOT FOUND\r\n");
        }
        out.print("Content-Type: text/plain; charset=utf-8\r\n");
        out.print("Content-Length: " + body.length() + "\r\n");
        out.print("\r\n");
        out.print(body.toString());
        out.close();
    }

    public static StringBuilder runScrapers() {
        StringBuilder response = new StringBuilder();
        // establish collector metrics
        long start;
        boolean succeeded;
        StringBuilder timing = new StringBuilder();
        StringBuilder success = new StringBuilder();

        // call all collectors
        // loadavg
        start = System.nanoTime();
        succeeded = scrapeLoadAvg(response);
        collectStats("loadavg", timing, success, start, System.nanoTime(), succeeded);

        // boottime & procs_running
        start = System.nanoTime();
        succeeded = scrapeSystemMisc(response);
        collectStats("os", timing, success, start, System.nanoTime(), succeeded);

        // arp counters
        start = System.nanoTime();
        succeeded = scrapeArp(response);
        collectStats("arp", timing, success, start, System.nanoTime(), succeeded);

        // interrupts, context switches, forks
        start = System.nanoTime();
        succeeded = scrapeVmstatTotals(response);
        collectStats("vmstat", timing, success, start, System.nanoTime(), succeeded);

        // swap
        start = System.nanoTime();
        succeeded = scrapeSwap(response);
        collectStats("swap", timing, success, start, System.nanoTime(), succeeded);

        // cpu states
        start = System.nanoTime();
        succeeded = scrapeCPUStates(response);
        collectStats("cpu", timing, success, start, System.nanoTime(), succeeded);

        // cpu frequency
        start = System.nanoTime();
        succeeded = scrapeCPUFreq(response);
        collectStats("cpufreq", timing, success, start, System.nanoTime(), succeeded);

        // emit collector duration & success metrics
        response.append("# HELP node_scrape_collector_duration_seconds node_exporter: Duration of a collector scrape.\n");
        response.append("# TYPE node_scrape_collector_duration_seconds gauge\n");
        response.append(timing.toString());
        response.append("# HELP node_scrape_collector_success node_exporter: Whether a collector succeeded.\n");
        response.append("# TYPE node_scrape_collector_success gauge\n");
        response.append(success.toString());
        return response;
    }

    private static void collectStats(String collectorName, StringBuilder timing, StringBuilder success, long start, long end, boolean succeeded) {
        double durationSeconds = (end - start) / 1000000000.0;
        timing.append("node_scrape_collector_duration_seconds{collector=\"").append(collectorName).append("\"} ").append(durationSeconds).append("\n");
        success.append("node_scrape_collector_success{collector=\"").append(collectorName).append("\"} ").append(succeeded ? "1" : "0").append("\n");
    }

    /*
     * getProcessOutput is a helper to retrieve the entire standard output of a command as a string.
     * This is mostly useful for the methods which scrape kstat output.
     */
    private static String getProcessOutput(ProcessBuilder pb) throws IOException {
        Process p = pb.start();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = p.getInputStream().read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString("UTF-8");
    }

    private static int getSystemPageSize() {
        String line;
        int pageSize = -1;
        try {
            Process p = new ProcessBuilder("pagesize").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            line = reader.readLine();
            if (line != null) {
                try {
                    pageSize = Integer.parseInt(line.trim());
                } catch (NumberFormatException e) {
                    System.err.println(e.getMessage());
                    return -1;
                }
            }
            reader.close();
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return -1;
        }
        return pageSize;
    }

    private static boolean scrapeVmstatTotals(StringBuilder sb) {
        try {
            Process p = new ProcessBuilder("vmstat", "-s").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, deviceInterrupts, cpuContextSwitches;
            deviceInterrupts = cpuContextSwitches = "";
            int forks, vforks;
            forks = vforks = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.endsWith("device interrupts")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 1) {
                        deviceInterrupts = parts[0];
                    }
                } else if (line.endsWith("cpu context switches")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 1) {
                        cpuContextSwitches = parts[0];
                    }
                // catches both the forks and vforks lines
                } else if (line.endsWith("forks")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 1) {
                        try {
                            forks = forks + Integer.parseInt(parts[0]);
                        } catch (NumberFormatException e) {
                            sb.append("# Error converting forks metric: ").append(line).append("\n");
                            return false;
                        }
                    }
                }
            }
            reader.close();

            // Output the simple interrupt/context switch counters
            sb.append("# HELP node_intr_total Total number of interrupts serviced.\n");
            sb.append("# TYPE node_intr_total counter\n");
            sb.append("node_intr_total ").append(deviceInterrupts).append("\n");
            sb.append("# HELP node_context_switches_total Total number of context switches.\n");
            sb.append("# TYPE node_context_switches_total counter\n");
            sb.append("node_context_switches_total ").append(cpuContextSwitches).append("\n");

            // Output node_forks_total metric (forks + vforks)
            sb.append("# HELP node_forks_total Total number of forks.\n");
            sb.append("# TYPE node_forks_total counter\n");
            sb.append("node_forks_total ").append(forks).append("\n");
            return true;
        } catch (IOException e) {
            System.err.println("Error reading static vmstat metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeArp(StringBuilder sb) {
        try {
            Process p = new ProcessBuilder("arp", "-a", "-n").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            HashMap<String, Integer> deviceCounts = new HashMap<String, Integer>();

            String line;
            while ((line = reader.readLine()) != null) {
                // Skip header lines
                if ((line.contains("Device") && line.contains("IP Address")) || (line.contains("------")) || (line.contains("Net to Media Table"))) {
                    continue;
                }
                line = line.trim();
                if (line.length() == 0) {
                    continue;
                }

                String[] parts = line.split("\\s+", 2);
                if (parts.length > 0) {
                    String device = parts[0];
                    Integer value = deviceCounts.containsKey(device) ? deviceCounts.get(device) : 0;
                    deviceCounts.put(device, value + 1);
                }
            }
            reader.close();

            // Output node_arp_entries metrics
            if (deviceCounts.isEmpty()) {
                sb.append("# Error reading arp metrics: no devices returned\n");
                return false;
            } else {
                sb.append("# HELP node_arp_entries Number of ARP entries for each device.\n");
                sb.append("# TYPE node_arp_entries gauge\n");
                for (Map.Entry<String, Integer> entry : deviceCounts.entrySet()) {
                    sb.append(String.format("node_arp_entries{device=\"%s\"} %d\n", entry.getKey(), entry.getValue()));
                }
                return true;
            }
        } catch (IOException e) {
            System.err.println("Error reading arp metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeCPUFreq(StringBuilder sb) {
        try {
            String fullOutput;
            String[] kstatChunks;
            KstatEntry[] kstats;

            fullOutput = getProcessOutput(new ProcessBuilder("kstat", "-m", "cpu_info"));
            kstatChunks = fullOutput.split("\n\n");

            kstats = new KstatEntry[kstatChunks.length];
            for (int i=0; i<kstatChunks.length; i++) {
                try {
                    kstats[i] = new KstatEntry(kstatChunks[i]);
                } catch (KstatException e) {
                    System.err.println(e.getMessage());
                    return false;
                }
            }

            boolean success = true;
            StringBuilder current = new StringBuilder();
            StringBuilder max = new StringBuilder();
            if (kstats.length > 0) {
                current.append("# HELP node_cpu_frequency_hertz Current CPU thread frequency in hertz.\n");
                current.append("# TYPE node_cpu_frequency_hertz gauge\n");
                max.append("# HELP node_cpu_frequency_max_hertz Maximum CPU thread frequency in hertz.\n");
                max.append("# TYPE node_cpu_frequency_max_hertz gauge\n");
            } else {
                System.err.println("No CPU info returned by kstat!");
                return false;
            }
            for (int i=0; i<kstats.length; i++) {
                String currentFreqHz, maxFreqMhz;
                long freqHz, freqMaxHz;
                currentFreqHz = kstats[i].getOrDefault("current_clock_Hz", "");
                maxFreqMhz = kstats[i].getOrDefault("clock_MHz", "");
                try {
                    freqHz = Long.parseLong(currentFreqHz);
                    freqMaxHz = Long.parseLong(maxFreqMhz) * 1000000;
                } catch (NumberFormatException e) {
                    sb.append("# Could not convert CPU ").append(i).append(" frequency values to longs: ").append(e.getMessage()).append("\n");
                    success = false;
                    continue;
                }

                if (freqHz > 0) {
                    current.append("node_cpu_frequency_hertz{cpu=\"").append(i).append("\"} ").append(freqHz).append("\n");
                } else {
                    current.append("# Error retrieving node_cpu_frequency_hertz metric for CPU ").append(i).append("\n");
                    success = false;
                }
                if (freqMaxHz > 0) {
                    max.append("node_cpu_frequency_max_hertz{cpu=\"").append(i).append("\"} ").append(freqMaxHz).append("\n");
                } else {
                    max.append("# Error retrieving node_cpu_frequency_max_hertz metric for CPU ").append(i).append("\n");
                    success = false;
                }
            }
            sb.append(current.toString());
            sb.append(max.toString());

            return success;
        } catch (IOException e) {
            System.err.println("Error reading CPU frequency metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeCPUStates(StringBuilder sb) {
        try {
            String fullOutput;
            String[] kstatChunks;
            KstatEntry[] kstats;

            fullOutput = getProcessOutput(new ProcessBuilder("kstat", "-m", "cpu", "-n", "sys"));
            kstatChunks = fullOutput.split("\n\n");

            kstats = new KstatEntry[kstatChunks.length];
            for (int i=0; i<kstatChunks.length; i++) {
                try {
                    kstats[i] = new KstatEntry(kstatChunks[i]);
                } catch (KstatException e) {
                    System.err.println(e.getMessage());
                    return false;
                }
            }

            boolean success = true;
            if (kstats.length > 0) {
                sb.append("# HELP node_cpu_seconds_total Seconds the CPUs spent in each mode.\n");
                sb.append("# TYPE node_cpu_seconds_total counter\n");
            } else {
                System.err.println("No CPUs returned by kstat!");
                return false;
            }

            // Emit metrics specific to each CPU
            for (int i=0; i<kstats.length; i++) {
                String cpu_nsec_idle, cpu_nsec_kernel, cpu_nsec_user, cpu_nsec_intr;
                long nsec_idle, nsec_kernel, nsec_user, nsec_intr;

                KstatEntry cpu = kstats[i];
                cpu_nsec_idle = cpu.getOrDefault("cpu_nsec_idle", "");
                cpu_nsec_kernel = cpu.getOrDefault("cpu_nsec_kernel", "");
                cpu_nsec_user = cpu.getOrDefault("cpu_nsec_user", "");
                cpu_nsec_intr = cpu.getOrDefault("cpu_nsec_intr", "");

                try {
                    nsec_idle = Long.parseLong(cpu_nsec_idle);
                    nsec_kernel = Long.parseLong(cpu_nsec_kernel);
                    nsec_user = Long.parseLong(cpu_nsec_user);
                    nsec_intr = Long.parseLong(cpu_nsec_intr);
                } catch (NumberFormatException e) {
                    sb.append("# Could not convert CPU ").append(i).append(" nsec totals to longs: ").append(e.getMessage()).append("\n");
                    success = false;
                    continue;
                }
                if (nsec_idle >= 0) {
                    sb.append("node_cpu_seconds_total{cpu=\"").append(i).append("\",mode=\"idle\"} ").append(nsec_idle/1e9).append("\n");
                } else {
                    sb.append("# Error retrieving node_cpu_seconds_total{mode=idle} metric for CPU ").append(i).append("\n");
                    success = false;
                }
                // Linux would call this mode "system"; this is an incompatibility
                if (nsec_kernel >= 0) {
                    sb.append("node_cpu_seconds_total{cpu=\"").append(i).append("\",mode=\"kernel\"} ").append(nsec_kernel/1e9).append("\n");
                } else {
                    sb.append("# Error retrieving node_cpu_seconds_total metric{mode=kernel} for CPU ").append(i).append("\n");
                    success = false;
                }
                if (nsec_user >= 0) {
                    sb.append("node_cpu_seconds_total{cpu=\"").append(i).append("\",mode=\"user\"} ").append(nsec_user/1e9).append("\n");
                } else {
                    sb.append("# Error retrieving node_cpu_seconds_total metric{mode=user} for CPU ").append(i).append("\n");
                    success = false;
                }
                // Linux would (roughly) call this mode "irq"; this is an incompatibility
                if (nsec_intr >= 0) {
                    sb.append("node_cpu_seconds_total{cpu=\"").append(i).append("\",mode=\"intr\"} ").append(nsec_intr/1e9).append("\n");
                } else {
                    sb.append("# Error retrieving node_cpu_seconds_total metric{mode=intr} for CPU ").append(i).append("\n");
                    success = false;
                }
            }

            return success;
        } catch (IOException e) {
            System.err.println("Error reading CPU metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeSwap(StringBuilder sb) {
        try {
            KstatEntry kstats;
            String fullOutput, swap_alloc, swap_avail, swap_free;
            long alloc, avail, free;

            fullOutput = getProcessOutput(new ProcessBuilder("kstat", "-m", "unix", "-i", "0", "-n", "vminfo"));

            try {
                kstats = new KstatEntry(fullOutput);
            } catch (KstatException e) {
                System.err.println(e.getMessage());
                return false;
            }
            swap_alloc = kstats.getOrDefault("swap_alloc", "");
            swap_avail = kstats.getOrDefault("swap_avail", "");
            swap_free = kstats.getOrDefault("swap_free", "");
            try {
                alloc = Long.parseLong(swap_alloc);
                avail = Long.parseLong(swap_avail);
                free = Long.parseLong(swap_free);
            } catch (NumberFormatException e) {
                sb.append("# Could not convert swap pages to integers" + e.getMessage() + "\n");
                return false;
            }

            boolean success = true;
            // Output each metric if available
            if (alloc >= 0) {
                sb.append("# HELP node_memory_SwapCached_bytes Memory reported by the kernel as allocated swap\n");
                sb.append("# TYPE node_memory_SwapCached_bytes gauge\n");
                sb.append("node_memory_SwapCached_bytes ").append(alloc * pagesize).append("\n");
            } else {
                sb.append("# Error retrieving node_memory_SwapCached_bytes metric\n");
                success = false;
            }
            if (free >= 0) {
                sb.append("# HELP node_memory_SwapFree_bytes Memory reported by the kernel as free for swap\n");
                sb.append("# TYPE node_memory_SwapFree_bytes gauge\n");
                sb.append("node_memory_SwapFree_bytes ").append(free * pagesize).append("\n");
            } else {
                sb.append("# Error retrieving node_memory_SwapFree_bytes metric\n");
                success = false;
            }
            if (avail >= 0) {
                sb.append("# HELP node_memory_SwapTotal_bytes Memory reported by the kernel as available swap\n");
                sb.append("# TYPE node_memory_SwapTotal_bytes gauge\n");
                sb.append("node_memory_SwapTotal_bytes ").append(avail * pagesize).append("\n");
            } else {
                sb.append("# Error retrieving node_memory_SwapTotal_bytes metric\n");
                success = false;
            }
            return success;
        } catch (IOException e) {
            System.err.println("Error reading swap metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeSystemMisc(StringBuilder sb) {
        // Improvements:
        // Load averages could be calculated from avenrun_1min (etc) / FSCALE
        // but FSCALE is only defined in sys/param.h and I don't want to reach for JNI yet
        // Hardcoding is good enough for Brendan Gregg so it's good enough for me too?
        // https://www.brendangregg.com/K9Toolkit/uptime
        //
        // node_procs_running isn't entirely accurate; it's the total number of processes
        // We can't tell at this stage how many of these are node_procs_blocked
        try {
            String fullOutput, bootTime, nproc;
            KstatEntry kstats;

            fullOutput = getProcessOutput(new ProcessBuilder("kstat", "-m", "unix", "-i", "0", "-n", "system_misc"));

            try {
                kstats = new KstatEntry(fullOutput);
            } catch (KstatException e) {
                System.err.println(e.getMessage());
                return false;
            }
            bootTime = kstats.getOrDefault("boot_time", "");
            nproc = kstats.getOrDefault("nproc", "");

            boolean success = true;
            // Output node_boot_time_seconds metric
            if (!bootTime.equals("")) {
                sb.append("# HELP node_boot_time_seconds Node boot time, in seconds since Unix epoch.\n");
                sb.append("# TYPE node_boot_time_seconds gauge\n");
                sb.append("node_boot_time_seconds ").append(bootTime).append("\n");
            } else {
                sb.append("# Error retrieving node_boot_time_seconds metric\n");
                success = false;
            }

            // Output node_procs_running metric
            if (!nproc.equals("")) {
                sb.append("# HELP node_procs_running Number of processes in runnable state.\n");
                sb.append("# TYPE node_procs_running gauge\n");
                sb.append("node_procs_running ").append(nproc).append("\n");
            } else {
                sb.append("# Error retrieving node_procs_running metric\n");
                success = false;
            }
            return success;
        } catch (IOException e) {
            System.err.println("Error reading system_misc metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeLoadAvg(StringBuilder sb) {
        try {
            Process p = new ProcessBuilder("uptime").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, one, five, fifteen;
            one = five = fifteen = "";

            line = reader.readLine();
            if (line != null) {
                Pattern loadNums = Pattern.compile("load average: (\\d\\.\\d\\d), (\\d\\.\\d\\d), (\\d\\.\\d\\d)");
                Matcher m = loadNums.matcher(line);
                if (m.find()) {
                    one = m.group(1);
                    five = m.group(2);
                    fifteen = m.group(3);
                }
            }
            reader.close();

            if (one.equals("") || five.equals("") || fifteen.equals("")) {
                sb.append("# Error parsing uptime metrics: ").append(line).append("\n");
                return false;
            } else {
                // Output metrics
                sb.append("# HELP node_load1 1m load average.\n");
                sb.append("# TYPE node_load1 gauge\n");
                sb.append("node_load1 ").append(one).append("\n");
                sb.append("# HELP node_load15 15m load average.\n");
                sb.append("# TYPE node_load15 gauge\n");
                sb.append("node_load15 ").append(fifteen).append("\n");
                sb.append("# HELP node_load5 5m load average.\n");
                sb.append("# TYPE node_load5 gauge\n");
                sb.append("node_load5 ").append(five).append("\n");
            }
            return true;
        } catch (IOException e) {
            System.err.println("Error reading uptime metrics: " + e.getMessage());
            return false;
        }
    }
}
