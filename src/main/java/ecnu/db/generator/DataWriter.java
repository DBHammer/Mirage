package ecnu.db.generator;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DataWriter {

    String outputPath;
    int generatorId;

    private static final String FILE_PATH_PATTERN = "%s/%s-%d-%d";

    int writeFileCounter = 0;

    ExecutorService executorService = Executors.newFixedThreadPool(6);

    public DataWriter(String outputPath, int generatorId) {
        this.outputPath = outputPath;
        this.generatorId = generatorId;
    }

    public void addWriteTask(String schemaName, StringBuilder[] keyData, String[] attData) {
        String fileName = String.format(FILE_PATH_PATTERN, outputPath, schemaName, generatorId, writeFileCounter);
        writeFileCounter++;
        executorService.submit(() -> {
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));
                for (int i = 0; i < keyData.length; i++) {
                    writer.write(keyData[i].toString());
                    writer.write(attData[i]);
                    writer.write(System.lineSeparator());
                }
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public boolean waitWriteFinish() throws InterruptedException {
        executorService.shutdown();
        return executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

}
