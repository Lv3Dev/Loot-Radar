package com.lootrader;

import java.util.Collections;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Assert;
import org.junit.Test;

public class LootRadarPluginTest
{
	/**
	 * Entry point for the Gradle {@code run} task. Launches the dev client with this plugin loaded.
	 */
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LootRadarPlugin.class);
		RuneLite.main(args);
	}

	@Test
	public void pluginClassLoads() throws Exception
	{
		Class<?> clazz = Class.forName("com.lootrader.LootRadarPlugin");
		Assert.assertEquals(LootRadarPlugin.class, clazz);
		Assert.assertTrue(Plugin.class.isAssignableFrom(clazz));

		PluginDescriptor descriptor = clazz.getAnnotation(PluginDescriptor.class);
		Assert.assertNotNull(descriptor);
		Assert.assertEquals("Loot Radar", descriptor.name());
		Assert.assertEquals(
			"Lists ground items in the loaded area with quantity, GE value, and high alch value",
			descriptor.description());
		Assert.assertArrayEquals(new String[]{"loot", "ground", "items", "alch"}, descriptor.tags());

		ConfigGroup group = LootRadarConfig.class.getAnnotation(ConfigGroup.class);
		Assert.assertNotNull(group);
		Assert.assertEquals(LootRadarConfig.GROUP, group.value());
		Assert.assertEquals("lootrader", group.value());
	}

	@Test
	public void hideWinsAndAlwaysShowSkipsValueFilters()
	{
		Set<String> show = Set.of("bones");
		Set<String> hide = Set.of("bones");
		Assert.assertFalse(LootRadarPlugin.isStackVisible("Bones", 50_000L, show, hide, 0));
		Assert.assertTrue(LootRadarPlugin.isStackVisible("Bones", 1L, show, Collections.emptySet(), 5_000));
	}

	@Test
	public void valueFiltersUseStackTotalAndZeroDisablesThem()
	{
		Set<String> none = Collections.emptySet();
		Assert.assertTrue(LootRadarPlugin.isStackVisible("Bones", 1L, none, none, 0));
		Assert.assertFalse(LootRadarPlugin.isStackVisible("Logs", 999L, none, none, 1_000));
		Assert.assertTrue(LootRadarPlugin.isStackVisible("Logs", 1_000L, none, none, 1_000));
	}

	@Test
	public void coinPilesStackAsCoins()
	{
		Assert.assertEquals(15L, LootRadarPlugin.coinStackQuantity(ItemID.COINS, 15));
		Assert.assertEquals(100L, LootRadarPlugin.coinStackQuantity(ItemID.COINS_100, 1));
		Assert.assertEquals(20_000L, LootRadarPlugin.coinStackQuantity(ItemID.COINS_10000, 2));
		Assert.assertEquals(-1L, LootRadarPlugin.coinStackQuantity(ItemID.RING_OF_COINS, 1));
	}

	@Test
	public void nameFiltersAreExact()
	{
		Set<String> none = Collections.emptySet();
		Assert.assertTrue(LootRadarPlugin.isStackVisible("Big bones", 1L, none, Set.of("bones"), 0));
		Assert.assertFalse(LootRadarPlugin.isStackVisible("Bones", 1L, none, Set.of("bones"), 0));
	}
}
