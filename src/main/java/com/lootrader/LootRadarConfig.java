package com.lootrader;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(LootRadarConfig.GROUP)
public interface LootRadarConfig extends Config
{
	String GROUP = "lootrader";

	@ConfigItem(
		keyName = "alwaysShow",
		name = "Items to always show",
		description = "Comma-separated item names that ignore the minimum GE value. Case-insensitive. An empty list disables this filter.",
		position = 1
	)
	default String alwaysShow()
	{
		return "";
	}

	@ConfigItem(
		keyName = "alwaysHide",
		name = "Items to always hide",
		description = "Comma-separated item names to hide. Case-insensitive. Hide wins over always show. An empty list disables this filter.",
		position = 2
	)
	default String alwaysHide()
	{
		return "";
	}

	@Range(min = 0)
	@Units(" gp")
	@ConfigItem(
		keyName = "hideBelowGe",
		name = "Min GE value",
		description = "Hide stacks whose GE total is below this. Compares the stack total, not the single-item price. 0 disables the filter.",
		position = 3
	)
	default int hideBelowGe()
	{
		return 0;
	}
}
