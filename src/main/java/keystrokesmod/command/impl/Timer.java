package keystrokesmod.command.impl;

import keystrokesmod.command.Command;
import keystrokesmod.command.CommandInput;
import keystrokesmod.module.ModuleManager;

import java.math.BigDecimal;
import java.util.List;

public class Timer extends Command {
    public Timer() {
        super("timer");
    }

    @Override
    public void execute(CommandInput input) {
        if (input.argumentCount() == 1 && input.getArgument(0).equalsIgnoreCase("reset")) {
            ModuleManager.timer.resetSpeed();
            replyWithHeader("&7Timer value reset to &b1&7.");
            return;
        }

        if (input.argumentCount() == 1 && input.getArgument(0).equalsIgnoreCase("value")) {
            replyWithHeader("&7Timer value: &b" + formatValue(ModuleManager.timer.getSpeed()));
            return;
        }

        if (input.argumentCount() != 2 || !input.getArgument(0).equalsIgnoreCase("set")) {
            replyWithHeader("&7Usage: &b" + prefixed("timer set <value>") + "&7, &b" + prefixed("timer reset") + "&7, or &b" + prefixed("timer value") + "&7.");
            return;
        }

        double value;
        try {
            value = Double.parseDouble(input.getArgument(1));
        }
        catch (NumberFormatException exception) {
            replyWithHeader("&7Timer value must be a number.");
            return;
        }

        if (!Double.isFinite(value)) {
            replyWithHeader("&7Timer value must be a finite number.");
            return;
        }

        ModuleManager.timer.setSpeed(value);
        replyWithHeader("&7Timer value set to &b" + formatValue(ModuleManager.timer.getSpeed()) + "&7.");
    }

    @Override
    public List<String> suggest(CommandInput input) {
        if (input.argumentCount() <= 1) {
            return filterSuggestions(input, "set", "reset", "value");
        }
        return super.suggest(input);
    }

    private String formatValue(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
