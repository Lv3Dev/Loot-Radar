package com.lootrader;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

class LootRadarPanel extends PluginPanel
{
	private enum SortKey
	{
		NAME("Name"),
		QUANTITY("Quantity"),
		GE_VALUE("GE value"),
		HIGH_ALCH("High alch value");

		private final String label;

		SortKey(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private final ItemManager itemManager;
	private final JPanel listPanel = new JPanel();
	private final JScrollPane scrollPane;
	private final JComboBox<SortKey> sortBox = new JComboBox<>(SortKey.values());
	private final JButton directionButton = new JButton("Descending");
	private final JLabel emptyLabel = new JLabel("No ground items", SwingConstants.CENTER);

	private List<LootRow> rows = new ArrayList<>();
	private SortKey sortKey = SortKey.GE_VALUE;
	private boolean ascending;

	LootRadarPanel(ItemManager itemManager)
	{
		super(false);
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel controls = new JPanel(new BorderLayout(6, 0));
		controls.setBorder(new EmptyBorder(6, 6, 6, 6));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);

		sortBox.setSelectedItem(SortKey.GE_VALUE);
		sortBox.setFocusable(false);
		sortBox.setToolTipText("Sort ground items");
		directionButton.setFocusable(false);
		directionButton.setMargin(new Insets(2, 8, 2, 8));
		directionButton.setToolTipText("Toggle ascending or descending order");
		directionButton.setForeground(ColorScheme.BRAND_ORANGE);

		controls.add(sortBox, BorderLayout.CENTER);
		controls.add(directionButton, BorderLayout.EAST);

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listPanel.setBorder(new EmptyBorder(0, 6, 6, 6));

		emptyLabel.setForeground(Color.WHITE);
		emptyLabel.setFont(FontManager.getRunescapeSmallFont());
		emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
		emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		emptyLabel.setBorder(new EmptyBorder(12, 4, 12, 4));

		scrollPane = new JScrollPane(listPanel);
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(controls, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);

		sortBox.addActionListener(event ->
		{
			Object selected = sortBox.getSelectedItem();
			if (selected instanceof SortKey)
			{
				sortKey = (SortKey) selected;
				render();
			}
		});
		directionButton.addActionListener(event ->
		{
			ascending = !ascending;
			directionButton.setText(ascending ? "Ascending" : "Descending");
			render();
		});

		render();
	}

	void setRows(List<LootRow> rows)
	{
		this.rows = new ArrayList<>(rows);
		int scroll = scrollPane.getVerticalScrollBar().getValue();
		render();
		SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(scroll));
	}

	private void render()
	{
		listPanel.removeAll();

		if (rows.isEmpty())
		{
			emptyLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, emptyLabel.getPreferredSize().height));
			listPanel.add(emptyLabel);
		}
		else
		{
			List<LootRow> sorted = new ArrayList<>(rows);
			sorted.sort(comparator());
			for (LootRow row : sorted)
			{
				listPanel.add(new LootRowPanel(row));
			}
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	private Comparator<LootRow> comparator()
	{
		Comparator<LootRow> comparator;
		switch (sortKey)
		{
			case NAME:
				comparator = Comparator.comparing(LootRow::getName, String.CASE_INSENSITIVE_ORDER);
				break;
			case QUANTITY:
				comparator = Comparator.comparingLong(LootRow::getQuantity);
				break;
			case HIGH_ALCH:
				comparator = Comparator.comparingLong(LootRow::getHaTotal);
				break;
			case GE_VALUE:
			default:
				comparator = Comparator.comparingLong(LootRow::getGeTotal);
				break;
		}

		if (!ascending)
		{
			comparator = comparator.reversed();
		}
		if (sortKey != SortKey.NAME)
		{
			comparator = comparator.thenComparing(LootRow::getName, String.CASE_INSENSITIVE_ORDER);
		}
		return comparator;
	}

	private final class LootRowPanel extends JPanel
	{
		private LootRowPanel(LootRow row)
		{
			setLayout(new BorderLayout(6, 0));
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setBorder(new CompoundBorder(
				new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(4, 4, 4, 4)));
			setAlignmentX(Component.LEFT_ALIGNMENT);

			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(36, 32));
			icon.setMinimumSize(new Dimension(36, 32));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			icon.setVerticalAlignment(SwingConstants.CENTER);
			itemManager.getImage(row.getItemId()).addTo(icon);

			JLabel name = new JLabel(row.getName());
			name.setForeground(Color.WHITE);
			name.setFont(FontManager.getRunescapeSmallFont());

			JLabel quantity = new JLabel("x" + QuantityFormatter.formatNumber(row.getQuantity()));
			quantity.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			quantity.setFont(FontManager.getRunescapeSmallFont());

			JPanel title = new JPanel(new BorderLayout(6, 0));
			title.setOpaque(false);
			title.add(name, BorderLayout.CENTER);
			title.add(quantity, BorderLayout.EAST);

			JLabel ge = valueLabel(
				"GE " + QuantityFormatter.formatNumber(row.getGeTotal())
					+ " (" + QuantityFormatter.formatNumber(row.getGeEach()) + " ea)",
				ColorScheme.GRAND_EXCHANGE_PRICE);
			JLabel ha = valueLabel(
				"HA " + QuantityFormatter.formatNumber(row.getHaTotal())
					+ " (" + QuantityFormatter.formatNumber(row.getHaEach()) + " ea)",
				ColorScheme.GRAND_EXCHANGE_ALCH);

			JPanel text = new JPanel(new GridLayout(3, 1, 0, 1));
			text.setOpaque(false);
			text.add(title);
			text.add(ge);
			text.add(ha);

			add(icon, BorderLayout.WEST);
			add(text, BorderLayout.CENTER);

			setToolTipText(row.getName()
				+ " x" + QuantityFormatter.formatNumber(row.getQuantity())
				+ " | GE " + QuantityFormatter.formatNumber(row.getGeTotal())
				+ " (" + QuantityFormatter.formatNumber(row.getGeEach()) + " ea)"
				+ ", HA " + QuantityFormatter.formatNumber(row.getHaTotal())
				+ " (" + QuantityFormatter.formatNumber(row.getHaEach()) + " ea)");

			Dimension preferred = getPreferredSize();
			setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
		}

		private JLabel valueLabel(String text, Color color)
		{
			JLabel label = new JLabel(text);
			label.setForeground(color);
			label.setFont(FontManager.getRunescapeSmallFont());
			return label;
		}
	}
}
