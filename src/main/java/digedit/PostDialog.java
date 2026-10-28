package digedit;

import digedit.Model.LinkType;
import digedit.Model.Post;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Form-fill dialog for creating or editing a post. Required fields are
 * validated inline (no pop-up error boxes), and the dialog remembers the
 * last author name so repeat posters don't retype it.
 */
public class PostDialog extends JDialog {
    private static String lastAuthor = "";

    private final JTextField author = new JTextField(24);
    private final JTextField title = new JTextField(24);
    private final JTextArea body = new JTextArea(7, 24);
    private final JComboBox<LinkType> linkType = new JComboBox<>(LinkType.values());
    private final JLabel error = new JLabel(" ");
    private boolean saved = false;

    /**
     * @param replyingTo title of the post being responded to, or null for a standalone post.
     *                   When non-null the dialog also asks for the link type.
     */
    public PostDialog(Window owner, String heading, Post existing, String replyingTo) {
        super(owner, heading, ModalityType.APPLICATION_MODAL);

        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        error.setForeground(new Color(0xC0392B));

        if (existing != null) {
            author.setText(existing.author);
            title.setText(existing.title);
            body.setText(existing.body);
        } else {
            author.setText(lastAuthor);
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.NORTHWEST;
        int row = 0;

        if (replyingTo != null) {
            c.gridx = 0; c.gridy = row; c.gridwidth = 2;
            JLabel ctx = new JLabel("<html>Responding to: <b>" + replyingTo + "</b></html>");
            form.add(ctx, c);
            c.gridwidth = 1; row++;
            addRow(form, c, row++, "Response type:", linkType);
        }
        addRow(form, c, row++, "Author *", author);
        addRow(form, c, row++, "Title *", title);

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        form.add(new JLabel("Message"), c);
        c.gridx = 1; c.fill = GridBagConstraints.BOTH; c.weightx = 1; c.weighty = 1;
        form.add(new JScrollPane(body), c);
        c.fill = GridBagConstraints.NONE; c.weighty = 0; row++;

        c.gridx = 1; c.gridy = row;
        form.add(error, c);

        JButton ok = new JButton(existing == null ? "Add Post" : "Save Changes");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> trySave());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> (author.getText().isBlank() ? author : title).requestFocusInWindow());
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        c.fill = GridBagConstraints.NONE;
    }

    private void trySave() {
        if (author.getText().isBlank()) { fail("Please enter an author name.", author); return; }
        if (title.getText().isBlank()) { fail("Please enter a title.", title); return; }
        lastAuthor = author.getText().trim();
        saved = true;
        dispose();
    }

    private void fail(String msg, JComponent field) {
        error.setText(msg);
        field.requestFocusInWindow();
    }

    /** Shows the dialog; returns true if the user saved. */
    public boolean showDialog() {
        setVisible(true);
        return saved;
    }

    public String getAuthor()   { return author.getText().trim(); }
    public String getPostTitle()   { return title.getText().trim(); }
    public String getBody()     { return body.getText().trim(); }
    public LinkType getLinkType() { return (LinkType) linkType.getSelectedItem(); }
}
