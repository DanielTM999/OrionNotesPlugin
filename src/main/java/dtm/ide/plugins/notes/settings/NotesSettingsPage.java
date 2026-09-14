package dtm.ide.plugins.notes.settings;

import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.ide.plugins.notes.ui.NotesPanel;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Objects;

public final class NotesSettingsPage implements PluginSettingsPage {
    private final NotesSettings settings;
    private final NotesPanel.TextResolver texts;
    private final JCheckBox restoreOpenNotes;
    private final JPanel view;

    public NotesSettingsPage(NotesSettings settings, NotesPanel.TextResolver texts) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.texts = Objects.requireNonNull(texts, "texts");
        restoreOpenNotes = new JCheckBox(text("settings.restoreOpenNotes",
                "Reopen notes from the previous session"));
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
        settings.save();
    }

    @Override
    public void onRestoreDefaults() {
        settings.restoreDefaults();
        settings.save();
        loadFromSettings();
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
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        content.add(new JPanel(), constraints);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private void loadFromSettings() {
        restoreOpenNotes.setSelected(settings.isRestoreOpenNotes());
    }

    private String text(String key, String fallback) {
        return texts.resolve(key, fallback);
    }
}
