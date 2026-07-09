package br.unisinos.omniphr;

import br.unisinos.omniphr.demo.DemoScenario;
import br.unisinos.omniphr.eval.Evaluation;
import br.unisinos.omniphr.test.SelfTest;

/**
 * Entry point of the OmniPHR reference implementation.
 *
 * Usage:
 *   java br.unisinos.omniphr.Main demo
 *   java br.unisinos.omniphr.Main eval [--full] [--duration seconds] [--seed n]
 *   java br.unisinos.omniphr.Main selftest
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "demo" : args[0];
        switch (command) {
            case "demo":
                new DemoScenario().run();
                break;
            case "eval": {
                boolean full = false;
                double duration = 60;
                long seed = 20170522;
                for (int i = 1; i < args.length; i++) {
                    switch (args[i]) {
                        case "--full":
                            full = true;
                            break;
                        case "--duration":
                            duration = Double.parseDouble(args[++i]);
                            break;
                        case "--seed":
                            seed = Long.parseLong(args[++i]);
                            break;
                        default:
                            System.err.println("unknown option " + args[i]);
                            System.exit(2);
                    }
                }
                new Evaluation(seed, duration, full).run();
                break;
            }
            case "selftest":
                System.exit(new SelfTest().run());
                break;
            default:
                System.err.println("usage: demo | eval [--full] [--duration s] [--seed n] | selftest");
                System.exit(2);
        }
    }

    private Main() {
    }
}
