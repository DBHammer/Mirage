package ecnu.db;

import ecnu.db.analyzer.TaskConfigurator;
import ecnu.db.analyzer.QueryInstantiate;
import ecnu.db.generator.DataGenerator;
import ecnu.db.schema.DDLGenerator;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "Mirage",
        version = {"${COMMAND-NAME} 0.1.0",
                "JVM: ${java.version} (${java.vendor} ${java.vm.name} ${java.vm.version})",
                "OS: ${os.name} ${os.version} ${os.arch}"},
        description = "tool for generating test database", sortOptions = false,
        subcommands = {TaskConfigurator.class, DataGenerator.class, DDLGenerator.class, QueryInstantiate.class},
        mixinStandardHelpOptions = true, usageHelpAutoWidth = true,
        header = {
                "@|green  _  _ _ ____ ____ ____ ____ |@",
                "@|green  |\\/| | |__/ |__| | __ |___  |@",
                "@|green  |  | | |  \\ |  | |__] |___  |@"}
)
public class MirageApp {
    public static void main(String... args) {
        int exitCode = new CommandLine(new MirageApp()).execute(args);
        System.exit(exitCode);
    }
}
