package dtm.ide.plugins.notes.settings;

import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.ide.plugins.notes.ui.NotesPanel;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Objects;

public final class NotesSettingsPage implements PluginSettingsPage {
    private final NotesSettings settings;
    private final NotesPanel.TextResolver texts;
    private final Runnable onSettingsChanged;
    private final JCheckBox restoreOpenNotes;
    private final JSpinner trashRetentionValue;
    private final JComboBox<String> trashRetentionUnit;
    private final JCheckBox keepTrashForever;
    private final JPanel view;

    public NotesSettingsPage(NotesSettings settings, NotesPanel.TextResolver texts) {
        this(settings, texts, () -> { });
    }

    public NotesSettingsPage(NotesSettings settings, NotesPanel.TextResolver texts, Runnable onSettingsChanged) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.onSettingsChanged = Objects.requireNonNull(onSettingsChanged, "onSettingsChanged");
        restoreOpenNotes = new JCheckBox(text("settings.restoreOpenNotes",
                "Reopen notes from the previous session"));
        trashRetentionValue = new JSpinner(new SpinnerNumberModel(1L, 1L, (long) Integer.MAX_VALUE, 1L));
        trashRetentionUnit = new JComboBox<>(new String[]{
                text("settings.trashRetention.hours", "hour(s)"),
                text("settings.trashRetention.days", "day(s)")
        });
        keepTrashForever = new JCheckBox(text("settings.trashRetention.forever", "Keep forever"));
        keepTrashForever.addActionListener(event -> updateTrashRetentionEnabled());
        view = buildView();
        loadFromSettings();
    }

    @Override
    public String getTitle() {
        return text("settings.title", "Orion Notes");
    }

    @Override
    public JComponent getView() {
        loadFromSettings();
        return view;
    }

    @Override
    public void onApply() {
        settings.setRestoreOpenNotes(restoreOpenNotes.isSelected());
        settings.setTrashRetentionHours(selectedTrashRetentionHours());
        settings.save();
        onSettingsChanged.run();
    }

    @Override
    public void onRestoreDefaults() {
        settings.restoreDefaults();
        settings.save();
        loadFromSettings();
        onSettingsChanged.run();
    }

    private JPanel buildView() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;

        JLabel sectionTitle = new JLabel(text("settings.session", "Session"));
        sectionTitle.setFont(sectionTitle.getFont().deriveFont(Font.BOLD, 18f));
        content.add(sectionTitle, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(14, 0, 0, 0);
        content.add(restoreOpenNotes, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(4, 24, 0, 0);
        JLabel description = new JLabel(text("settings.restoreOpenNotes.description",
                "When disabled, notes remain closed when Orion starts or a project is opened."));
        Color secondary = UIManager.getColor("Label.disabledForeground");
        if (secondary != null) description.setForeground(secondary);
        content.add(description, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(28, 0, 0, 0);
        JLabel trashTitle = new JLabel(text("settings.trash", "Trash"));
        trashTitle.setFont(trashTitle.getFont().deriveFont(Font.BOLD, 18f));
        content.add(trashTitle, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(14, 0, 0, 0);
        JPanel retention = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        retention.add(new JLabel(text("settings.trashRetention", "Automatically delete items after")));
        retention.add(trashRetentionValue);
        retention.add(trashRetentionUnit);
        content.add(retention, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(8, 0, 0, 0);
        content.add(keepTrashForever, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(4, 24, 0, 0);
        JLabel trashDescription = new JLabel(text("settings.trashRetention.description",
                "Expired items are permanently deleted. The minimum period is 1 hour."));
        if (secondary != null) trashDescription.setForeground(secondary);
        content.add(trashDescription, constraints);

        constraints.gridy++;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        content.add(new JPanel(), constraints);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private void loadFromSettings() {
        restoreOpenNotes.setSelected(settings.isRestoreOpenNotes());
        long hours = settings.getTrashRetentionHours();
        boolean forever = hours == NotesSettings.KEEP_TRASH_FOREVER;
        keepTrashForever.setSelected(forever);
        if (!forever && hours % 24 == 0) {
            trashRetentionValue.setValue(hours / 24);
            trashRetentionUnit.setSelectedIndex(1);
        } else {
            trashRetentionValue.setValue(forever ? 1L : hours);
            trashRetentionUnit.setSelectedIndex(0);
        }
        updateTrashRetentionEnabled();
    }

    private long selectedTrashRetentionHours() {
        if (keepTrashForever.isSelected()) return NotesSettings.KEEP_TRASH_FOREVER;
        long value = ((Number) trashRetentionValue.getValue()).longValue();
        return trashRetentionUnit.getSelectedIndex() == 1 ? value * 24 : value;
    }

    private void updateTrashRetentionEnabled() {
        boolean enabled = !keepTrashForever.isSelected();
        trashRetentionValue.setEnabled(enabled);
        trashRetentionUnit.setEnabled(enabled);
    }

    private String text(String key, String fallback) {
        return texts.resolve(key, fallback);
    }
}
