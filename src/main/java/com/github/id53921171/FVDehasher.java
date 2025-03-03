package com.github.id53921171;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcResponse;

public final class FVDehasher
{
    private static final Map<String, String> metadata = loadMetadata();
    private static final AtomicInteger hashMatches = new AtomicInteger();
    private static final AtomicInteger hashTotal = new AtomicInteger();

    public static void main(String[] args) throws InterruptedException
    {
        final ExecutorService service = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors());

        for (String path : new File(System.getProperty(
                "user.dir")).list())
        {
            final String LOWER_PATH = path.toLowerCase();

            if (LOWER_PATH.endsWith(".warc.gz") ||
                    LOWER_PATH.endsWith(".warc"))
            {
                service.submit(() -> extractWarc(path));
            }
        }

        service.shutdown();
        service.awaitTermination(1, TimeUnit.DAYS);
        System.out.printf("Hashed Names: %s%n", hashTotal);
        System.out.printf("Hash Matches: %s%n", hashMatches);
        writeEntries();
    }

    private static Map<String, String> loadMetadata()
    {
        final Map<String, String> assetMap = new ConcurrentHashMap<>();
        final File infile = new File("assethash.txt");
        final int NAME_INDEX = 0;
        final int HASH_INDEX = 1;

        System.out.printf("Loading %s...%n", infile.getName());

        try (Scanner scanner = new Scanner(infile))
        {
            int currentLine = 0;

            while (scanner.hasNextLine())
            {
                final String[] parts = scanner.nextLine(
                ).trim().split(" ");

                currentLine++;

                if (parts.length == 2)
                {
                    assetMap.put(parts[HASH_INDEX], parts[NAME_INDEX]);
                }
                else
                {
                    throw new IllegalStateException(String.format(
                            "The metadata file is malformed at line %d.",
                            currentLine));
                }
            }
        }
        catch (IllegalStateException err)
        {
            System.err.println(err.getMessage());
        }
        catch (IOException err)
        {
            System.err.println("The metadata file is unavailable.");
        }
        finally
        {
            System.out.printf("Metadata Entries: %d%n",
                    assetMap.size());

            return assetMap;
        }
    }

    private static void extractWarc(String path)
    {
        System.out.printf("Extracting %s...%n", path);

        try (WarcReader reader = new WarcReader(FileChannel.open(
                Paths.get(path))))
        {
            final int INITIAL_ERROR_CODE = 400;

            for (WarcRecord record : reader)
            {
                if (record instanceof WarcResponse &&
                        record.contentType().base().equals(MediaType.HTTP))
                {
                    final WarcResponse response = (WarcResponse) record;

                    if (response.http().status() < INITIAL_ERROR_CODE)
                    {
                        final Path OUTPATH = Paths.get(findPath(
                                response.target()));

                        Files.createDirectories(OUTPATH.getParent());
                        Files.copy(response.http().body().stream(),
                                OUTPATH,
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            System.out.printf("Extracted %s%n", path);
        }
        catch (IOException err)
        {
            System.err.println(err);
        }
    }

    private static String findPath(String link)
    {
        final int PATH_INDEX = 30;
        final String PATH = link.substring(PATH_INDEX);

        if (PATH.contains("hashed"))
        {
            final String HASH = PATH.substring(PATH.lastIndexOf('/') + 1,
                    PATH.lastIndexOf('.'));

            hashTotal.incrementAndGet();

            if (metadata.containsKey(HASH))
            {
                hashMatches.incrementAndGet();

                return PATH.replace(HASH,
                        metadata.remove(HASH));
            }
        }

        return PATH;
    }

    private static void writeEntries()
    {
        final File outfile = new File("entries.txt");

        System.out.printf("Writing remaining entries to %s...%n",
                outfile.getName());

        try (PrintWriter writer = new PrintWriter(outfile))
        {
            for (Map.Entry<String, String> entry : metadata.entrySet())
            {
                writer.printf("%s %s%n", entry.getValue(),
                        entry.getKey());
            }

            System.out.println("Wrote entries");
        }
        catch (FileNotFoundException err)
        {
            System.err.println("The entries are unwritable.");
        }
    }
}
