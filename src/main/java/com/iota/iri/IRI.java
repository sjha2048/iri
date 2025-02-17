package com.iota.iri;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.iota.iri.conf.BaseIotaConfig;
import com.iota.iri.conf.Config;
import com.iota.iri.conf.ConfigFactory;
import com.iota.iri.conf.IotaConfig;
import com.iota.iri.network.NetworkInjectionConfiguration;
import com.iota.iri.service.API;
import com.iota.iri.utils.IotaUtils;
import com.iota.iri.service.restserver.resteasy.RestEasy;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * Main IOTA Reference Implementation (IRI) starting class.
 * <p>
 *     The IRI software enables the Tangle to operate. Individuals can run IRI to operates Nodes.
 *     The Node running the IRI software enables your device to communicate with neighbors
 *     in the peer-to-peer network that the Tangle operates on.
 * </p>
 * <p>
 *     IRI implements all the core functionality necessary for participating in an IOTA network as a full node.
 *     This includes, but is not limited to:
 *     <ul>
 *         <li>Receiving and broadcasting transactions through TCP and UDP.</li>
 *         <li>Handling of HTTP requests from clients.</li>
 *         <li>Tracking and validating Milestones.</li>
 *         <li>Loading custom modules that extend the API.</li>
 *     </ul>
 * </p>
 *
 * @see <a href="https://docs.iota.org/docs/node-software/0.1/iri/introduction/overview">Online documentation on iri</a>
 */
public class IRI {

    public static final String MAINNET_NAME = "IRI";
    public static final String TESTNET_NAME = "IRI Testnet";

    /**
     * The entry point of IRI.
     * Starts by configuring the logging settings, then proceeds to {@link IRILauncher#main(String[])}
     * The log level is set to INFO by default.
     *
     * @param args Configuration arguments. See {@link BaseIotaConfig} for a list of all options.
     * @throws Exception If we fail to start the IRI launcher.
     */
    public static void main(String[] args) throws Exception {
        // Logging is configured first before ANY references to Logger or LoggerFactory.
        // Any public method or field accessors needed in IRI should be put in IRI and then delegate to IRILauncher.
        // That ensures that future code does not need to know about this setup.
        configureLogging();
        IRILauncher.main(args);
    }

    /**
     * Reads the logging configuration file and logging level from system properties. You can set this values as
     * arguments to the Java VM by passing <code>-Dlogback.configurationFile=/path/to/config.xml -Dlogging-level=DEBUG</code>
     * to the Java VM. If no system properties are specified the logback default values and logging-level INFO will
     * be used.
     */
    private static void configureLogging() {
        String config = System.getProperty("logback.configurationFile");
        String level = System.getProperty("logging-level", "").toUpperCase();
        switch (level) {
            case "OFF":
            case "ERROR":
            case "WARN":
            case "INFO":
            case "DEBUG":
            case "TRACE":
                break;
            case "ALL":
                level = "TRACE";
                break;
            default:
                level = "INFO";
                break;
        }
        System.getProperties().put("logging-level", level);
        System.out.println("Logging - property 'logging-level' set to: [" + level + "]");
        if (config != null) {
            System.out.println("Logging - alternate logging configuration file specified at: '" + config + "'");
        }
    }

    private static class IRILauncher {
        private static final Logger log = LoggerFactory.getLogger(IRILauncher.class);

        public static Iota iota;
        public static API api;
        public static IXI ixi;

        /**
         * Starts IRI. Setup is as follows:
         * <ul>
         *     <li>Load the configuration.</li>
         *     <li>Create {@link Iota}, {@link IXI} and {@link API}.</li>
         *     <li>Listen for node shutdown.</li>
         *     <li>Initialize {@link Iota}, {@link IXI} and {@link API} using their <tt>init()</tt> methods.</li>
         * </ul>
         *
         * If no exception is thrown, the node starts synchronizing with the network, and the API can be used.
         *
         * @param args Configuration arguments. See {@link BaseIotaConfig} for a list of all options.
         * @throws Exception If any of the <tt>init()</tt> methods failed to initialize.
         */
        public static void main(String [] args) throws Exception {
            IotaConfig config = createConfiguration(args);
            String version = IotaUtils.getIriVersion();
            log.info("Welcome to {} {}", config.isTestnet() ? TESTNET_NAME : MAINNET_NAME, version);

            Injector injector = Guice.createInjector(
                    new MainInjectionConfiguration(config),
                    new NetworkInjectionConfiguration(config));

            iota = injector.getInstance(Iota.class);
            ixi = injector.getInstance(IXI.class);
            api = injector.getInstance(API.class);

            shutdownHook();

            try {
                iota.init();
                //TODO redundant parameter but we will touch this when we refactor IXI
                ixi.init(config.getIxiDir());
                api.init(new RestEasy(config));
                log.info("IOTA Node initialised correctly.");
            } catch (Exception e) {
                log.error("Exception during IOTA node initialisation: ", e);
                throw e;
            }
        }

        /**
         * Gracefully shuts down the node by calling <tt>shutdown()</tt> on {@link Iota}, {@link IXI} and {@link API}.
         * Exceptions during shutdown are caught and logged.
         */
        private static void shutdownHook() {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down IOTA node, please hold tight...");
                try {
                    ixi.shutdown();
                    api.shutDown();
                    iota.shutdown();
                } catch (Exception e) {
                    log.error("Exception occurred shutting down IOTA node: ", e);
                }
            }, "Shutdown Hook"));
        }

        private static IotaConfig createConfiguration(String[] args) {
            IotaConfig iotaConfig = null;
            String message = "Configuration is created using ";
            try {
                boolean testnet = isTestnet(args);
                File configFile = chooseConfigFile(args);
                if (configFile != null) {
                    iotaConfig = ConfigFactory.createFromFile(configFile, testnet);
                    message += configFile.getName() + " and command line args";
                }
                else {
                    iotaConfig = ConfigFactory.createIotaConfig(testnet);
                    message += "command line args only";
                }
                JCommander jCommander = iotaConfig.parseConfigFromArgs(args);
                if (iotaConfig.isHelp()) {
                    jCommander.usage();
                    System.exit(0);
                }
            } catch (IOException | IllegalArgumentException e) {
                log.error("There was a problem reading configuration from file: {}", e.getMessage());
                log.debug("", e);
                System.exit(-1);
            } catch (ParameterException e) {
                log.error("There was a problem parsing commandline arguments: {}", e.getMessage());
                log.debug("", e);
                System.exit(-1);
            }

            log.info(message);
            log.info("parsed the following cmd args: {}", Arrays.toString(args));
            return iotaConfig;
        }

        /**
         * We are connected to testnet when {@link Config#TESTNET_FLAG} is passed in program startup,
         * following with <code>true</code>
         * 
         * @param args the list of program startup arguments
         * @return <code>true</code> if this is testnet, otherwise <code>false</code>
         */
        private static boolean isTestnet(String[] args) {
            int index = ArrayUtils.indexOf(args, Config.TESTNET_FLAG);
            if (index != -1 && args.length > index+1) {
                Boolean bool = BooleanUtils.toBooleanObject(args[index+1]);
                return bool == null ? false : bool;
            }
            
            return false;
        }

        /**
         * Parses the command line arguments for a config file that can be provided by parameter <code>-c</code>
         * or parameter <code>--config</code>. If no filename was provided we fall back to <code>iota.ini</code> file.
         * If no <code>iota.ini</code> file can be found return null.
         *
         * @param args command line arguments passed to main method.
         * @return File the chosen file to use as config, or null.
         */
        private static File chooseConfigFile(String[] args) {
            int index = Math.max(ArrayUtils.indexOf(args, "-c"), ArrayUtils.indexOf(args, "--config"));
            if (index != -1) {
                try {
                    String fileName = args[++index];
                    return new File(fileName);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "The file after `-c` or `--config` isn't specified or can't be parsed.", e);
                }
            }
            else if (IotaConfig.CONFIG_FILE.exists()) {
                return IotaConfig.CONFIG_FILE;
            }
            return null;
        }
    }
}
