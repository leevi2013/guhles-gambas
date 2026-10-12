package de.mrgamba.oregamba;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class GambaMod implements ModInitializer {
    public static final String MOD_ID = "oregamba";

    private static final String COIN_OBJECTIVE = "gamba_coins";
    private static final int STARTING_COINS = 1_000;
    private static final int DEFAULT_BET = 10;
    private static final int BET_STEP = 10;
    private static final int MAX_BET = 50_000_000;
    private static final Random RANDOM = Random.create();

    private static final Map<UUID, Integer> BETS = new HashMap<>();
    private static final Set<UUID> INVALID_BET = new HashSet<>();
    private static final Map<UUID, GambaScreenHandler> ACTIVE_MENUS = new HashMap<>();
    private static final Map<UUID, SpinState> ACTIVE_SPINS = new HashMap<>();

    private static final Symbol[] SYMBOLS = new Symbol[] {
            new Symbol(Items.COAL_ORE, "Kohleerz", 0.5),
            new Symbol(Items.IRON_ORE, "Eisenerz", 1.2),
            new Symbol(Items.GOLD_ORE, "Golderz", 2.0),
            new Symbol(Items.DIAMOND_ORE, "Diamanterz", 5.0),
            new Symbol(Items.EMERALD_ORE, "Smaragderz", 10.0),
            new Symbol(Items.ANCIENT_DEBRIS, "Antiker Schrott", 25.0)
    };

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            coinScore(server, handler.player.getName().getString());
        });

        ServerTickEvents.END_SERVER_TICK.register(GambaMod::tickSpins);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("gamba")
                        .executes(context -> {
                            openGui(context.getSource().getPlayerOrThrow());
                            return 1;
                        })
                        .then(literal("bet")
                                .then(argument("amount", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                            setBetFromCommand(player, IntegerArgumentType.getInteger(context, "amount"));
                                            return 1;
                                        })))
                        .then(literal("coins")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    tell(player, "Dein Kontostand: " + balance(player) + " Coins.", Formatting.AQUA);
                                    return 1;
                                }))
                        .then(literal("help")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    tell(player, "/gamba – Slot-GUI öffnen", Formatting.AQUA);
                                    tell(player, "/gamba bet <zahl> – positiven Einsatz setzen", Formatting.AQUA);
                                    tell(player, "/gamba coins – Kontostand anzeigen", Formatting.AQUA);
                                    return 1;
                                }))));
    }

    private static void setBetFromCommand(ServerPlayerEntity player, int amount) {
        UUID id = player.getUuid();
        if (ACTIVE_SPINS.containsKey(id)) {
            tell(player, "Der Einsatz kann während einer Drehung nicht geändert werden.", Formatting.YELLOW);
            return;
        }
        if (amount <= 0) {
            INVALID_BET.add(id);
            tell(player, "Ungültiger Einsatz: Der Einsatz muss größer als 0 sein. Drehen ist gesperrt. Setze einen neuen Einsatz mit /gamba bet <zahl>.", Formatting.RED);
            refreshIfOpen(player);
            return;
        }
        if (amount > MAX_BET) {
            INVALID_BET.add(id);
            tell(player, "Der Einsatz ist zu hoch. Das Maximum beträgt " + MAX_BET + " Coins. Drehen ist gesperrt, bis du einen gültigen Einsatz setzt.", Formatting.RED);
            refreshIfOpen(player);
            return;
        }

        BETS.put(id, amount);
        INVALID_BET.remove(id);
        tell(player, "Einsatz auf " + amount + " Coins gesetzt.", Formatting.GREEN);
        refreshIfOpen(player);
    }

    private static void openGui(ServerPlayerEntity player) {
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("Ore Gamba").formatted(Formatting.BLUE, Formatting.BOLD);
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity playerEntity) {
                Inventory inventory = new SimpleInventory(27);
                GambaScreenHandler menu = new GambaScreenHandler(syncId, playerInventory, inventory);
                if (playerEntity instanceof ServerPlayerEntity serverPlayer) {
                    ACTIVE_MENUS.put(serverPlayer.getUuid(), menu);
                    refreshGui(serverPlayer, menu);
                }
                return menu;
            }
        });
    }

    public static void handleGuiClick(ServerPlayerEntity player, GambaScreenHandler menu, int slot) {
        UUID id = player.getUuid();

        if (slot == 20) { // lower bet
            if (ACTIVE_SPINS.containsKey(id)) {
                tell(player, "Warte, bis sich die Slot-Maschine nicht mehr dreht.", Formatting.YELLOW);
                return;
            }
            if (INVALID_BET.contains(id)) {
                BETS.put(id, DEFAULT_BET);
                INVALID_BET.remove(id);
                tell(player, "Einsatz zurückgesetzt auf " + DEFAULT_BET + " Coins.", Formatting.GREEN);
                refreshGui(player, menu);
                return;
            }
            int bet = currentBet(player) - BET_STEP;
            if (bet <= 0) {
                tell(player, "Der Einsatz darf niemals 0 oder negativ sein. Nutze /gamba bet <zahl> für einen anderen Einsatz.", Formatting.RED);
                return;
            }
            BETS.put(id, bet);
            refreshGui(player, menu);
            return;
        }

        if (slot == 24) { // raise bet
            if (ACTIVE_SPINS.containsKey(id)) {
                tell(player, "Warte, bis sich die Slot-Maschine nicht mehr dreht.", Formatting.YELLOW);
                return;
            }
            int bet = currentBet(player);
            if (INVALID_BET.contains(id)) {
                bet = DEFAULT_BET;
                INVALID_BET.remove(id);
            } else {
                if (bet > MAX_BET - BET_STEP) {
                    tell(player, "Der Einsatz darf höchstens " + MAX_BET + " Coins betragen.", Formatting.RED);
                    return;
                }
                bet += BET_STEP;
            }
            BETS.put(id, bet);
            refreshGui(player, menu);
            return;
        }

        if (slot == 22) {
            spin(player, menu);
        }
    }

    private static void spin(ServerPlayerEntity player, GambaScreenHandler menu) {
        UUID id = player.getUuid();
        if (ACTIVE_SPINS.containsKey(id)) {
            tell(player, "Die Slot-Maschine dreht sich bereits.", Formatting.YELLOW);
            return;
        }
        if (INVALID_BET.contains(id)) {
            tell(player, "Drehen blockiert: Dein letzter Einsatz war ungültig. Setze zuerst einen positiven Einsatz mit /gamba bet <zahl>.", Formatting.RED);
            refreshGui(player, menu);
            return;
        }

        int bet = currentBet(player);
        if (bet <= 0) {
            INVALID_BET.add(id);
            tell(player, "Drehen blockiert: Der Einsatz muss größer als 0 sein.", Formatting.RED);
            refreshGui(player, menu);
            return;
        }
        int coins = balance(player);
        if (coins < bet) {
            tell(player, "Nicht genug Coins. Dein Kontostand: " + coins + ", Einsatz: " + bet + ".", Formatting.RED);
            return;
        }

        setBalance(player.getServer(), player.getName().getString(), coins - bet);
        int durationTicks = 40 + RANDOM.nextInt(41); // 40–80 ticks = 2–4 seconds
        ACTIVE_SPINS.put(id, new SpinState(player.getName().getString(), bet, durationTicks));
        tell(player, "Die Slot-Maschine dreht sich …", Formatting.AQUA);
        refreshGui(player, menu);
    }

    private static void tickSpins(MinecraftServer server) {
        Iterator<Map.Entry<UUID, SpinState>> iterator = ACTIVE_SPINS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SpinState> entry = iterator.next();
            UUID id = entry.getKey();
            SpinState state = entry.getValue();
            state.ticksLeft--;

            ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(id);
            GambaScreenHandler menu = ACTIVE_MENUS.get(id);
            if (state.ticksLeft > 0 && state.ticksLeft % 2 == 0 && onlinePlayer != null && menu != null) {
                setRandomReels(menu.getGambaInventory());
                refreshGui(onlinePlayer, menu);
            }

            if (state.ticksLeft <= 0) {
                iterator.remove();
                finishSpin(server, id, state, onlinePlayer, menu);
            }
        }
    }

    private static void finishSpin(MinecraftServer server, UUID id, SpinState state,
                                   ServerPlayerEntity onlinePlayer, GambaScreenHandler menu) {
        int first = randomSymbolIndex();
        int second = randomSymbolIndex();
        int third = randomSymbolIndex();
        if (menu != null) {
            setReels(menu.getGambaInventory(), first, second, third);
        }

        if (first == second && second == third) {
            Symbol symbol = SYMBOLS[first];
            int payout = (int) Math.round(state.bet * symbol.multiplier());
            ScoreAccess score = coinScore(server, state.playerName);
            score.setScore(Math.max(0, score.getScore() + payout));
            if (onlinePlayer != null) {
                String multiplier = String.format(Locale.ROOT, "%.1fx", symbol.multiplier());
                tell(onlinePlayer, "Treffer! 3× " + symbol.name() + " – Auszahlung: " + payout + " Coins (" + multiplier + "). Kontostand: " + score.getScore() + ".", Formatting.GREEN);
            }
        } else if (onlinePlayer != null) {
            tell(onlinePlayer, "Keine drei gleichen Symbole – Einsatz von " + state.bet + " Coins verloren.", Formatting.RED);
        }

        if (onlinePlayer != null && menu != null) {
            refreshGui(onlinePlayer, menu);
        }
    }

    private static void refreshIfOpen(ServerPlayerEntity player) {
        GambaScreenHandler menu = ACTIVE_MENUS.get(player.getUuid());
        if (menu != null) {
            refreshGui(player, menu);
        }
    }

    private static void refreshGui(ServerPlayerEntity player, GambaScreenHandler menu) {
        Inventory inventory = menu.getGambaInventory();

        // Decorative blue glass frame. Reel slots are intentionally left untouched here.
        for (int slot = 0; slot < 27; slot++) {
            if (isReel(slot)) {
                if (inventory.getStack(slot).isEmpty()) {
                    setSymbol(inventory, slot, randomSymbolIndex());
                }
            } else if (slot == 4) {
                inventory.setStack(slot, namedItem(Items.BOOK,
                        "Coins: " + balance(player) + " | Einsatz: " + currentBet(player), Formatting.AQUA));
            } else if (slot == 20) {
                inventory.setStack(slot, namedItem(Items.REDSTONE,
                        "Einsatz −" + BET_STEP, Formatting.RED));
            } else if (slot == 24) {
                inventory.setStack(slot, namedItem(Items.EMERALD,
                        "Einsatz +" + BET_STEP, Formatting.GREEN));
            } else if (slot == 22) {
                if (ACTIVE_SPINS.containsKey(player.getUuid())) {
                    inventory.setStack(slot, namedItem(Items.CLOCK, "Dreht sich …", Formatting.YELLOW));
                } else if (INVALID_BET.contains(player.getUuid())) {
                    inventory.setStack(slot, namedItem(Items.BARRIER, "Einsatz ungültig – Drehung gesperrt", Formatting.RED));
                } else {
                    inventory.setStack(slot, namedItem(Items.LIME_DYE, "DREHEN", Formatting.GREEN));
                }
            } else {
                inventory.setStack(slot, namedItem(Items.BLUE_STAINED_GLASS_PANE, " ", Formatting.BLUE));
            }
        }

        if (INVALID_BET.contains(player.getUuid())) {
            inventory.setStack(4, namedItem(Items.BOOK, "Einsatz ungültig – /gamba bet <zahl>", Formatting.RED));
        }
        menu.sendContentUpdates();
    }

    private static void setRandomReels(Inventory inventory) {
        setSymbol(inventory, 10, randomSymbolIndex());
        setSymbol(inventory, 13, randomSymbolIndex());
        setSymbol(inventory, 16, randomSymbolIndex());
    }

    private static void setReels(Inventory inventory, int first, int second, int third) {
        setSymbol(inventory, 10, first);
        setSymbol(inventory, 13, second);
        setSymbol(inventory, 16, third);
    }

    private static void setSymbol(Inventory inventory, int slot, int symbolIndex) {
        Symbol symbol = SYMBOLS[symbolIndex];
        inventory.setStack(slot, namedItem(symbol.item(), symbol.name(), Formatting.WHITE));
    }

    private static int randomSymbolIndex() {
        return RANDOM.nextInt(SYMBOLS.length);
    }

    private static boolean isReel(int slot) {
        return slot == 10 || slot == 13 || slot == 16;
    }

    private static ItemStack namedItem(Item item, String name, Formatting formatting) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(formatting));
        return stack;
    }

    private static int currentBet(ServerPlayerEntity player) {
        return BETS.getOrDefault(player.getUuid(), DEFAULT_BET);
    }

    private static int balance(ServerPlayerEntity player) {
        return coinScore(player.getServer(), player.getName().getString()).getScore();
    }

    private static void setBalance(MinecraftServer server, String playerName, int amount) {
        coinScore(server, playerName).setScore(Math.max(0, amount));
    }

    private static ScoreAccess coinScore(MinecraftServer server, String playerName) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(COIN_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(COIN_OBJECTIVE, ScoreboardCriterion.DUMMY,
                    Text.literal("Gamba Coins"), ScoreboardCriterion.RenderType.INTEGER, false, null);
        }

        ScoreHolder holder = ScoreHolder.fromName(playerName);
        if (scoreboard.getScore(holder, objective) == null) {
            ScoreAccess freshScore = scoreboard.getOrCreateScore(holder, objective);
            freshScore.setScore(STARTING_COINS);
            return freshScore;
        }
        return scoreboard.getOrCreateScore(holder, objective);
    }

    private static void tell(ServerPlayerEntity player, String message, Formatting color) {
        player.sendMessage(Text.literal(message).formatted(color), false);
    }

    public static void onMenuClosed(ServerPlayerEntity player, GambaScreenHandler menu) {
        ACTIVE_MENUS.remove(player.getUuid(), menu);
    }

    private record Symbol(Item item, String name, double multiplier) { }

    private static final class SpinState {
        private final String playerName;
        private final int bet;
        private int ticksLeft;

        private SpinState(String playerName, int bet, int ticksLeft) {
            this.playerName = playerName;
            this.bet = bet;
            this.ticksLeft = ticksLeft;
        }
    }
}
