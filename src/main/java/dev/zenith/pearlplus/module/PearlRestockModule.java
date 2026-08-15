package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class PearlRestockModule extends Module {
    private static final long CONTAINER_OPEN_TIMEOUT_MS = 5_000L;
    private static final int INVENTORY_PRIORITY = 1000;

    private enum State {
        IDLE,
        PENDING,
        PATHING,
        WAITING_OPEN,
        WITHDRAWING
    }

    private State state = State.IDLE;
    private BlockPos startPos;
    private RequestFuture pathFuture;
    private RequestFuture withdrawFuture;
    private long waitOpenUntilMs;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.restock.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Stopped.class, event -> abort("Bot stopped"))
        );
    }

    @Override
    public void onDisable() {
        reset();
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public void requestAfterLoad(BlockPos startPos) {
        if (!PLUGIN_CONFIG.restock.enabled) {
            return;
        }
        if (isBusy()) {
            info("Pearl restock already running, ignoring new request");
            return;
        }
        if (!isEnabled()) {
            syncEnabledFromConfig();
        }
        this.startPos = startPos;
        // Start on the next bot tick. requestAfterLoad is invoked from a
        // Baritone executed listener; InteractWithProcess then calls
        // onLostControl() in the same tick and would cancel a new click.
        state = State.PENDING;
        info("Queued pearl restock from ["
            + PLUGIN_CONFIG.restock.x + ", "
            + PLUGIN_CONFIG.restock.y + ", "
            + PLUGIN_CONFIG.restock.z + "]");
        discordAndIngameNotification(Embed.builder()
            .title("Restocking Pearls")
            .description("Walking to restock container")
            .primaryColor());
    }

    private void onTick(ClientBotTick event) {
        if (state == State.IDLE) {
            return;
        }
        if (!canOperate()) {
            abort("Bot is not ready");
            return;
        }
        switch (state) {
            case PENDING -> startPathing();
            case PATHING -> tickPathing();
            case WAITING_OPEN -> tickWaitingOpen();
            case WITHDRAWING -> tickWithdrawing();
            default -> {
            }
        }
    }

    private void startPathing() {
        info("Restocking one stack of pearls from ["
            + PLUGIN_CONFIG.restock.x + ", "
            + PLUGIN_CONFIG.restock.y + ", "
            + PLUGIN_CONFIG.restock.z + "]");
        pathFuture = BARITONE.rightClickBlock(
            PLUGIN_CONFIG.restock.x,
            PLUGIN_CONFIG.restock.y,
            PLUGIN_CONFIG.restock.z
        );
        state = State.PATHING;
    }

    private void tickPathing() {
        if (pathFuture == null || !pathFuture.isCompleted()) {
            return;
        }
        if (!pathFuture.isAccepted()) {
            finish("Restock Failed", "Could not open restock container", false);
            return;
        }
        waitOpenUntilMs = System.currentTimeMillis() + CONTAINER_OPEN_TIMEOUT_MS;
        state = State.WAITING_OPEN;
    }

    private void tickWaitingOpen() {
        int containerId = openContainerId();
        if (containerId != 0) {
            List<InventoryAction> withdraw = InventoryActionMacros.withdraw(
                containerId,
                PearlRestockModule::isEnderPearlStack,
                1
            );
            if (withdraw.isEmpty()) {
                if (System.currentTimeMillis() > waitOpenUntilMs) {
                    finish("Restock Failed", "No ender pearls in restock container", false);
                }
                return;
            }
            List<InventoryAction> actions = new ArrayList<>(withdraw);
            actions.add(new CloseContainer(containerId));
            withdrawFuture = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(actions)
                .priority(INVENTORY_PRIORITY)
                .build());
            state = State.WITHDRAWING;
            return;
        }
        if (System.currentTimeMillis() > waitOpenUntilMs) {
            finish("Restock Failed", "Restock container did not open", false);
        }
    }

    private void tickWithdrawing() {
        if (withdrawFuture == null || !withdrawFuture.isCompleted()) {
            return;
        }
        if (!withdrawFuture.isAccepted()) {
            finish("Restock Failed", "Could not withdraw pearls", false);
            return;
        }
        if (!hasEnderPearlsInInventory()) {
            finish("Restock Failed", "Inventory full or withdraw failed", false);
            return;
        }
        finish("Pearls Restocked", "Took one stack from the restock container", true);
    }

    private void abort(String reason) {
        if (state == State.IDLE) {
            return;
        }
        finish("Restock Failed", reason, false);
    }

    private void finish(String title, String description, boolean success) {
        BlockPos returnPos = startPos;
        boolean pathHome = PLUGIN_CONFIG.autoLoad.returnToStartPos && returnPos != null && canOperate();
        reset();
        var builder = Embed.builder()
            .title(title)
            .description(description);
        if (success) {
            builder.successColor();
        } else {
            builder.errorColor();
        }
        discordAndIngameNotification(builder);
        if (pathHome) {
            BARITONE.pathTo(returnPos.x(), returnPos.y(), returnPos.z())
                .addExecutedListener(f -> discordAndIngameNotification(
                    Embed.builder()
                        .description("Returned to start pos")
                        .successColor()
                ));
        }
    }

    private void reset() {
        state = State.IDLE;
        startPos = null;
        pathFuture = null;
        withdrawFuture = null;
        waitOpenUntilMs = 0L;
    }

    private static boolean canOperate() {
        Proxy proxy = Proxy.getInstance();
        return proxy != null && proxy.isConnected() && !proxy.isInQueue() && !proxy.hasActivePlayer();
    }

    private static int openContainerId() {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getInventoryCache() == null) {
            return 0;
        }
        return CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
    }

    private static boolean isEnderPearlStack(ItemStack stack) {
        return stack != null
            && stack != Container.EMPTY_STACK
            && stack.getId() == ItemRegistry.ENDER_PEARL.id();
    }

    private static boolean hasEnderPearlsInInventory() {
        if (CACHE == null || CACHE.getPlayerCache() == null) {
            return false;
        }
        var inventory = CACHE.getPlayerCache().getPlayerInventory();
        if (inventory == null) {
            return false;
        }
        int end = Math.min(inventory.size(), 46);
        for (int i = 9; i < end; i++) {
            var stack = inventory.get(i);
            if (stack instanceof ItemStack item && isEnderPearlStack(item)) {
                return true;
            }
        }
        return false;
    }
}
