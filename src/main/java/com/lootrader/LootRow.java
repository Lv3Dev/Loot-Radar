package com.lootrader;

import lombok.Value;

@Value
class LootRow
{
	int itemId;
	String name;
	long quantity;
	long geEach;
	long geTotal;
	long haEach;
	long haTotal;
}
