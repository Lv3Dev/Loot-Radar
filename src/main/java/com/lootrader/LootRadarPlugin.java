package com.lootrader;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.ItemComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Loot Radar",
	description = "Lists ground items in the loaded area with quantity, GE value, and high alch value",
	tags = {"loot", "ground", "items", "alch"}
)
public class LootRadarPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private LootRadarConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	private final Map<PileKey, Long> piles = new HashMap<>();

	private LootRadarPanel panel;
	private NavigationButton navButton;

	/**
	 * Item events are ignored while a scene is loading. A scene load despawns every pile
	 * without {@link ItemDespawned}, then sends a fresh spawn for each pile. The list is
	 * rebuilt from the scene once the player is logged in.
	 */
	private boolean acceptItemEvents;
	private int sceneBaseX = Integer.MIN_VALUE;
	private int sceneBaseY = Integer.MIN_VALUE;
	private int[] loadedRegions = new int[0];

	@Provides
	LootRadarConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LootRadarConfig.class);
	}

	@Override
	protected void startUp()
	{
		migrateValueFilter();

		panel = new LootRadarPanel(itemManager);
		navButton = NavigationButton.builder()
			.tooltip("Loot Radar")
			.icon(createIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			rebuildFromScene();
		}

		log.debug("Loot Radar started");
	}

	@Override
	protected void shutDown()
	{
		acceptItemEvents = false;
		piles.clear();
		sceneBaseX = Integer.MIN_VALUE;
		sceneBaseY = Integer.MIN_VALUE;
		loadedRegions = new int[0];

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;

		log.debug("Loot Radar stopped");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!LootRadarConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		clientThread.invoke(this::publish);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			rebuildFromScene();
			return;
		}

		if (state == GameState.LOADING
			|| state == GameState.HOPPING
			|| state == GameState.LOGIN_SCREEN
			|| state == GameState.CONNECTION_LOST)
		{
			acceptItemEvents = false;
			piles.clear();
			sceneBaseX = Integer.MIN_VALUE;
			sceneBaseY = Integer.MIN_VALUE;
			loadedRegions = new int[0];
			publish();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (acceptItemEvents && sceneChanged())
		{
			rebuildFromScene();
		}
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded event)
	{
		WorldView worldView = event.getWorldView();
		if (worldView == null)
		{
			return;
		}
		int worldViewId = worldView.getId();
		if (piles.entrySet().removeIf(entry -> entry.getKey().worldViewId == worldViewId))
		{
			publish();
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		if (!prepareItemEvent())
		{
			return;
		}
		TileItem item = event.getItem();
		if (item == null || item.getQuantity() <= 0)
		{
			return;
		}
		addQuantity(event.getTile(), item, item.getQuantity());
		publish();
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (!prepareItemEvent())
		{
			return;
		}
		TileItem item = event.getItem();
		if (item == null)
		{
			return;
		}
		addQuantity(event.getTile(), item, -item.getQuantity());
		publish();
	}

	@Subscribe
	public void onItemQuantityChanged(ItemQuantityChanged event)
	{
		if (!prepareItemEvent())
		{
			return;
		}

		TileItem item = event.getItem();
		PileKey key = pileKey(event.getTile(), item);
		if (key == null)
		{
			return;
		}

		if (!piles.containsKey(key))
		{
			if (event.getNewQuantity() > 0)
			{
				piles.put(key, (long) event.getNewQuantity());
				publish();
			}
			return;
		}

		addQuantity(event.getTile(), item, event.getNewQuantity() - event.getOldQuantity());
		publish();
	}

	/**
	 * Name lists are already lower-case. Hide wins over always show. Always show skips the minimum GE value.
	 * The value filter compares the stack GE total. A threshold of 0 disables it.
	 */
	static boolean isStackVisible(String name, long geTotal, Set<String> alwaysShow, Set<String> alwaysHide, int minGe)
	{
		String key = name == null ? "" : name.trim().toLowerCase(Locale.ENGLISH);
		if (alwaysHide.contains(key))
		{
			return false;
		}
		if (alwaysShow.contains(key))
		{
			return true;
		}
		return minGe <= 0 || geTotal >= minGe;
	}

	private boolean prepareItemEvent()
	{
		if (!acceptItemEvents || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		if (sceneChanged())
		{
			rebuildFromScene();
			return false;
		}
		return true;
	}

	private void rebuildFromScene()
	{
		piles.clear();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			acceptItemEvents = false;
			sceneBaseX = Integer.MIN_VALUE;
			sceneBaseY = Integer.MIN_VALUE;
			loadedRegions = new int[0];
			publish();
			return;
		}

		acceptItemEvents = true;
		WorldView top = client.getTopLevelWorldView();
		if (top != null)
		{
			sceneBaseX = top.getBaseX();
			sceneBaseY = top.getBaseY();
			int[] regions = top.getMapRegions();
			loadedRegions = regions == null ? new int[0] : regions.clone();
			scanWorldView(top);

			IndexedObjectSet<? extends WorldView> children = top.worldViews();
			if (children != null)
			{
				for (WorldView child : children)
				{
					scanWorldView(child);
				}
			}
		}
		publish();
	}

	private void scanWorldView(WorldView worldView)
	{
		if (worldView == null)
		{
			return;
		}
		Scene scene = worldView.getScene();
		if (scene == null)
		{
			return;
		}
		Tile[][][] tiles = scene.getTiles();
		if (tiles == null)
		{
			return;
		}

		for (Tile[][] plane : tiles)
		{
			if (plane == null)
			{
				continue;
			}
			for (Tile[] column : plane)
			{
				if (column == null)
				{
					continue;
				}
				for (Tile tile : column)
				{
					if (tile == null || tile.getGroundItems() == null)
					{
						continue;
					}
					for (TileItem item : tile.getGroundItems())
					{
						if (item != null && item.getQuantity() > 0)
						{
							addQuantity(tile, item, item.getQuantity());
						}
					}
				}
			}
		}
	}

	private boolean sceneChanged()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		WorldView top = client.getTopLevelWorldView();
		if (top == null)
		{
			return false;
		}
		if (top.getBaseX() != sceneBaseX || top.getBaseY() != sceneBaseY)
		{
			return true;
		}
		int[] regions = top.getMapRegions();
		return regions != null && !sameRegions(regions);
	}

	private boolean sameRegions(int[] regions)
	{
		if (regions.length != loadedRegions.length)
		{
			return false;
		}
		int[] current = regions.clone();
		int[] previous = loadedRegions.clone();
		Arrays.sort(current);
		Arrays.sort(previous);
		return Arrays.equals(current, previous);
	}

	private void addQuantity(Tile tile, TileItem item, int delta)
	{
		if (delta == 0)
		{
			return;
		}
		PileKey key = pileKey(tile, item);
		if (key == null)
		{
			return;
		}
		long next = piles.getOrDefault(key, 0L) + delta;
		if (next <= 0)
		{
			piles.remove(key);
		}
		else
		{
			piles.put(key, next);
		}
	}

	private PileKey pileKey(Tile tile, TileItem item)
	{
		if (tile == null || item == null || item.getId() <= 0)
		{
			return null;
		}
		WorldPoint point = tile.getWorldLocation();
		LocalPoint local = tile.getLocalLocation();
		if (point == null || local == null)
		{
			return null;
		}
		return new PileKey(local.getWorldView(), point.getX(), point.getY(), point.getPlane(), item.getId());
	}

	private void publish()
	{
		LootRadarPanel target = panel;
		if (target == null)
		{
			return;
		}
		List<LootRow> rows = buildRows();
		SwingUtilities.invokeLater(() -> target.setRows(rows));
	}

	private List<LootRow> buildRows()
	{
		Map<Integer, Long> quantities = new HashMap<>();
		Map<Integer, String> names = new HashMap<>();

		for (Map.Entry<PileKey, Long> entry : piles.entrySet())
		{
			if (entry.getValue() <= 0)
			{
				continue;
			}
			int itemId = entry.getKey().itemId;
			ItemComposition composition = itemManager.getItemComposition(itemId);
			String name = composition.getName();
			if (name == null)
			{
				continue;
			}
			name = name.trim();
			if (name.isEmpty() || "null".equalsIgnoreCase(name))
			{
				continue;
			}

			// Noted and placeholder ids stay as their own rows. Coin pile ids share one Coins stack.
			long coins = coinStackQuantity(itemId, entry.getValue());
			if (coins >= 0)
			{
				quantities.merge(ItemID.COINS, coins, Long::sum);
				names.put(ItemID.COINS, "Coins");
				continue;
			}
			quantities.merge(itemId, entry.getValue(), Long::sum);
			names.putIfAbsent(itemId, name);
		}

		Set<String> alwaysShow = parseNames(config.alwaysShow());
		Set<String> alwaysHide = parseNames(config.alwaysHide());
		int minGe = config.hideBelowGe();

		List<LootRow> rows = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : quantities.entrySet())
		{
			int itemId = entry.getKey();
			long quantity = entry.getValue();
			String name = names.get(itemId);
			long geEach = Math.max(0L, itemManager.getItemPrice(itemId));
			long haEach = itemManager.getItemComposition(itemId).getHaPrice();
			long geTotal = geEach * quantity;
			long haTotal = haEach * quantity;
			if (!isStackVisible(name, geTotal, alwaysShow, alwaysHide, minGe))
			{
				continue;
			}
			rows.add(new LootRow(itemId, name, quantity, geEach, geTotal, haEach, haTotal));
		}
		return rows;
	}

	/**
	 * Coin piles use separate item ids for fixed amounts. Returns the gp amount, or -1 when the id is not coins.
	 */
	static long coinStackQuantity(int itemId, long quantity)
	{
		if (quantity <= 0)
		{
			return -1;
		}

		switch (itemId)
		{
			case ItemID.COINS:
			case ItemID.COINS_1:
				return quantity;
			case ItemID.COINS_2:
				return quantity * 2;
			case ItemID.COINS_3:
				return quantity * 3;
			case ItemID.COINS_4:
				return quantity * 4;
			case ItemID.COINS_5:
				return quantity * 5;
			case ItemID.COINS_25:
				return quantity * 25;
			case ItemID.COINS_100:
				return quantity * 100;
			case ItemID.COINS_250:
				return quantity * 250;
			case ItemID.COINS_1000:
				return quantity * 1000;
			case ItemID.COINS_10000:
				return quantity * 10000;
			default:
				return -1;
		}
	}

	/**
	 * The removed "only above" setting was a second minimum. Fold a saved value into {@code hideBelowGe}
	 * so the stricter floor remains, then drop the old key.
	 */
	private void migrateValueFilter()
	{
		String onlyAboveRaw = configManager.getConfiguration(LootRadarConfig.GROUP, "onlyAboveGe");
		if (onlyAboveRaw == null)
		{
			return;
		}

		int onlyAbove = parseNonNegative(onlyAboveRaw);
		int hideBelow = Math.max(0, config.hideBelowGe());
		int merged = hideBelow;
		if (onlyAbove > 0)
		{
			long strictFloor = (long) onlyAbove + 1;
			if (strictFloor > merged)
			{
				merged = strictFloor > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) strictFloor;
			}
		}
		if (merged != hideBelow)
		{
			configManager.setConfiguration(LootRadarConfig.GROUP, "hideBelowGe", merged);
		}
		configManager.unsetConfiguration(LootRadarConfig.GROUP, "onlyAboveGe");
	}

	private static int parseNonNegative(String raw)
	{
		try
		{
			return Math.max(0, Integer.parseInt(raw.trim()));
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	private static Set<String> parseNames(String csv)
	{
		Set<String> names = new HashSet<>();
		if (csv == null || csv.trim().isEmpty())
		{
			return names;
		}
		for (String part : csv.split(","))
		{
			String name = part.trim().toLowerCase(Locale.ENGLISH);
			if (!name.isEmpty())
			{
				names.add(name);
			}
		}
		return names;
	}

	private static BufferedImage createIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(new Color(212, 175, 55));
		graphics.fillOval(1, 1, 14, 14);
		graphics.setColor(new Color(92, 64, 18));
		graphics.drawOval(1, 1, 13, 13);
		graphics.drawLine(4, 8, 12, 8);
		graphics.drawLine(8, 4, 8, 12);
		graphics.dispose();
		return image;
	}

	@Value
	private static class PileKey
	{
		int worldViewId;
		int x;
		int y;
		int plane;
		int itemId;
	}
}
