package digedit;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entry point: opens the database and shows the main window. */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to the default look and feel
            }
            Database db = new Database("digedit.db");
            new MainFrame(db).setVisible(true);
        });
    }
}
