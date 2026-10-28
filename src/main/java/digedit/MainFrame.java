package digedit;

import digedit.Model.Discussion;
import digedit.Model.Link;
import digedit.Model.LinkType;
import digedit.Model.Post;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Main application window (WIMP layout):
 *   menu bar + toolbar  (top)
 *   graph canvas        (center)
 *   details panel       (right)
 *   status bar          (bottom)
 */
public class MainFrame extends JFrame implements GraphCanvas.Listener {

    /** One undoable step: a label for the menu and the code that reverses it. */
    private record UndoStep(String label, Runnable undo) {}

    private final Database db;
    private final GraphCanvas canvas = new GraphCanvas(this);
    private final JScrollPane canvasScroll = new JScrollPane(canvas);
    private final JComboBox<Discussion> discussionBox = new JComboBox<>();
    private final JTextField searchField = new JTextField(16);
    private final JLabel status = new JLabel(" ");
    private final JLabel stats = new JLabel(" ");
    private final Deque<UndoStep> undoStack = new ArrayDeque<>();
    private Discussion current;
    private boolean highContrast = false;

    // details panel widgets
    private final JLabel detailTitle = new JLabel();
    private final JLabel detailMeta = new JLabel();
    private final JTextArea detailBody = new JTextArea();
    private final DefaultListModel<Connection> connModel = new DefaultListModel<>();
    private final JList<Connection> connList = new JList<>(connModel);
    private final JPanel detailButtons = new JPanel(new GridLayout(0, 1, 0, 6));

    /** One row in the "Connections" list of the details panel. */
    private record Connection(Post other, String text) {
        @Override public String toString() { return text; }
    }

    private final int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    // ----------------------------------------------------------------- actions

    private final Action newPostAction = action("New Post", "Add a new post to this discussion",
            KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcut), e -> newPostAtViewCenter());
    private final Action replyAction = action("Reply", "Write a response to the selected post",
            KeyStroke.getKeyStroke(KeyEvent.VK_R, shortcut), e -> reply(canvas.getSelectedPost()));
    private final Action linkAction = action("Link…", "Connect the selected post to another post (then click the target)",
            KeyStroke.getKeyStroke(KeyEvent.VK_L, shortcut), e -> canvas.startLinkMode(canvas.getSelectedPost()));
    private final Action editAction = action("Edit", "Edit the selected post",
            KeyStroke.getKeyStroke(KeyEvent.VK_E, shortcut), e -> edit(canvas.getSelectedPost()));
    private final Action deleteAction = action("Delete", "Delete the selected post or link (Ctrl+Z to undo)",
            KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), e -> deleteSelection());
    private final Action undoAction = action("Undo", "Undo the last change",
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), e -> undo());
    private final Action zoomInAction = action("Zoom In", "Make everything larger",
            KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, shortcut), e -> zoomBy(1.15));
    private final Action zoomOutAction = action("Zoom Out", "Make everything smaller",
            KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, shortcut), e -> zoomBy(1 / 1.15));
    private final Action zoomResetAction = action("Actual Size", "Reset zoom to 100%",
            KeyStroke.getKeyStroke(KeyEvent.VK_0, shortcut), e -> { canvas.setZoom(1); updateStats(); });

    public MainFrame(Database db) {
        super("DigEdit");
        this.db = db;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(900, 560));
        setLocationRelativeTo(null);

        setJMenuBar(buildMenuBar());
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildToolbar(), BorderLayout.NORTH);
        canvasScroll.getVerticalScrollBar().setUnitIncrement(16);
        canvasScroll.getHorizontalScrollBar().setUnitIncrement(16);
        canvasScroll.setBorder(BorderFactory.createEmptyBorder());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvasScroll, buildDetailsPanel());
        split.setResizeWeight(1.0);
        split.setDividerLocation(920);
        root.add(split, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(root);

        // canvas-only keys
        canvas.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc");
        canvas.getActionMap().put("esc", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (canvas.isLinkMode()) canvas.cancelLinkMode();
                else canvas.select((Post) null);
            }
        });

        reloadDiscussions(-1);
        updateActions();
        setStatus("Welcome to DigEdit. Drag posts to arrange them, double-click to edit, right-click for options.");
    }

    // ------------------------------------------------------------- building UI

    private Action action(String name, String tip, KeyStroke key, ActionListener body) {
        Action a = new AbstractAction(name) {
            public void actionPerformed(ActionEvent e) { body.actionPerformed(e); }
        };
        a.putValue(Action.SHORT_DESCRIPTION, tip);
        if (key != null) a.putValue(Action.ACCELERATOR_KEY, key);
        return a;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(menuItem("New Discussion…", e -> newDiscussion()));
        file.add(menuItem("Rename Discussion…", e -> renameDiscussion()));
        file.add(menuItem("Delete Discussion…", e -> deleteDiscussion()));
        file.addSeparator();
        file.add(menuItem("Exit", e -> dispose()));
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');
        edit.add(undoAction);
        edit.addSeparator();
        edit.add(newPostAction);
        edit.add(replyAction);
        edit.add(linkAction);
        edit.add(editAction);
        edit.add(deleteAction);
        edit.addSeparator();
        JMenuItem find = menuItem("Find…", e -> searchField.requestFocusInWindow());
        find.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcut));
        edit.add(find);
        bar.add(edit);

        JMenu view = new JMenu("View");
        view.setMnemonic('V');
        view.add(zoomInAction);
        view.add(zoomOutAction);
        view.add(zoomResetAction);
        view.addSeparator();
        JCheckBoxMenuItem contrast = new JCheckBoxMenuItem("High Contrast");
        contrast.addActionListener(e -> {
            highContrast = contrast.isSelected();
            canvas.setHighContrast(highContrast);
            legend.repaint();
        });
        view.add(contrast);
        bar.add(view);

        JMenu help = new JMenu("Help");
        help.setMnemonic('H');
        help.add(menuItem("Quick Guide", e -> showGuide()));
        help.add(menuItem("About DigEdit", e -> JOptionPane.showMessageDialog(this,
                "DigEdit 1.0\nA visual editor for discussion graphs.\nCOMP 485 – Human-Computer Interaction, CSUN",
                "About DigEdit", JOptionPane.INFORMATION_MESSAGE)));
        bar.add(help);
        return bar;
    }

    private static JMenuItem menuItem(String text, ActionListener l) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(l);
        return item;
    }

    private JToolBar buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        tb.add(new JLabel("Discussion: "));
        discussionBox.setMaximumSize(new Dimension(320, 30));
        discussionBox.setToolTipText("Switch between discussions");
        discussionBox.addActionListener(e -> {
            Discussion d = (Discussion) discussionBox.getSelectedItem();
            if (d != null && (current == null || d.id() != current.id())) openDiscussion(d);
        });
        tb.add(discussionBox);
        tb.addSeparator(new Dimension(16, 0));

        for (Action a : List.of(newPostAction, replyAction, linkAction, editAction, deleteAction)) {
            tb.add(toolButton(a));
        }
        tb.addSeparator(new Dimension(16, 0));
        tb.add(toolButton(undoAction));
        tb.addSeparator(new Dimension(16, 0));
        JButton zOut = toolButton(zoomOutAction); zOut.setText("–");
        JButton zIn = toolButton(zoomInAction); zIn.setText("+");
        tb.add(zOut);
        tb.add(zIn);

        tb.add(Box.createHorizontalGlue());
        tb.add(new JLabel("Search: "));
        searchField.setMaximumSize(new Dimension(220, 30));
        searchField.setToolTipText("Highlights posts whose title, author, or text match. Enter jumps to the next match.");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { searchChanged(); }
            public void removeUpdate(DocumentEvent e) { searchChanged(); }
            public void changedUpdate(DocumentEvent e) { searchChanged(); }
        });
        searchField.addActionListener(e -> nextMatch());
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        searchField.getActionMap().put("clear", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { searchField.setText(""); canvas.requestFocusInWindow(); }
        });
        tb.add(searchField);
        return tb;
    }

    private static JButton toolButton(Action a) {
        JButton b = new JButton(a);
        b.setFocusable(false);
        b.setMargin(new Insets(4, 10, 4, 10));
        return b;
    }

    private JPanel legend;

    private JComponent buildDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.setPreferredSize(new Dimension(320, 100));
        panel.setMinimumSize(new Dimension(240, 100));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 16f));
        detailMeta.setForeground(Color.GRAY);
        detailTitle.setAlignmentX(0); detailMeta.setAlignmentX(0);
        top.add(detailTitle);
        top.add(Box.createVerticalStrut(4));
        top.add(detailMeta);
        panel.add(top, BorderLayout.NORTH);

        detailBody.setEditable(false);
        detailBody.setLineWrap(true);
        detailBody.setWrapStyleWord(true);
        detailBody.setFont(UIManager.getFont("Label.font").deriveFont(14f));
        detailBody.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        connList.setToolTipText("Double-click to jump to that post");
        connList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && connList.getSelectedValue() != null) {
                    canvas.select(connList.getSelectedValue().other());
                }
            }
        });
        JPanel connPanel = new JPanel(new BorderLayout(0, 4));
        connPanel.add(new JLabel("Connections (double-click to go there):"), BorderLayout.NORTH);
        connPanel.add(new JScrollPane(connList), BorderLayout.CENTER);
        connPanel.setPreferredSize(new Dimension(100, 150));

        JSplitPane middle = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(detailBody), connPanel);
        middle.setResizeWeight(0.6);
        middle.setBorder(BorderFactory.createEmptyBorder());
        panel.add(middle, BorderLayout.CENTER);

        legend = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int y = 14;
                for (LinkType t : LinkType.values()) {
                    g.setColor(GraphCanvas.linkColor(t, false));
                    g.setStroke(GraphCanvas.linkStroke(t, 2f));
                    g.drawLine(4, y, 54, y);
                    g.setColor(getForeground());
                    g.drawString(t.label, 64, y + 5);
                    y += 22;
                }
            }
        };
        legend.setPreferredSize(new Dimension(200, 70));
        legend.setToolTipText("Link types: colour and line style both show the type");

        JPanel bottom = new JPanel(new BorderLayout(0, 10));
        bottom.add(detailButtons, BorderLayout.CENTER);
        JPanel legendBox = new JPanel(new BorderLayout());
        legendBox.setBorder(BorderFactory.createTitledBorder("Legend"));
        legendBox.add(legend);
        bottom.add(legendBox, BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xD0D0D8)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        bar.add(status, BorderLayout.CENTER);
        bar.add(stats, BorderLayout.EAST);
        return bar;
    }

    // ---------------------------------------------------------- discussions

    private void reloadDiscussions(int selectId) {
        List<Discussion> list = db.listDiscussions();
        if (list.isEmpty()) list = List.of(db.addDiscussion("Untitled discussion"));
        discussionBox.removeAllItems();
        current = null;
        Discussion toOpen = list.get(0);
        for (Discussion d : list) {
            discussionBox.addItem(d);
            if (d.id() == selectId) toOpen = d;
        }
        discussionBox.setSelectedItem(toOpen);
        if (current == null || current.id() != toOpen.id()) openDiscussion(toOpen);
    }

    private void openDiscussion(Discussion d) {
        current = d;
        undoStack.clear();
        canvas.cancelLinkMode();
        canvas.select((Post) null);
        setTitle("DigEdit — " + d.title());
        reload();
        canvasScroll.getViewport().setViewPosition(new Point(0, 0));
        setStatus("Opened \"" + d.title() + "\".");
    }

    private void newDiscussion() {
        String name = JOptionPane.showInputDialog(this, "Name for the new discussion:", "New Discussion",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        Discussion d = db.addDiscussion(name.trim());
        reloadDiscussions(d.id());
        setStatus("Created discussion \"" + d.title() + "\". Double-click the canvas to add the first post.");
    }

    private void renameDiscussion() {
        String name = (String) JOptionPane.showInputDialog(this, "New name:", "Rename Discussion",
                JOptionPane.PLAIN_MESSAGE, null, null, current.title());
        if (name == null || name.isBlank()) return;
        db.renameDiscussion(current.id(), name.trim());
        reloadDiscussions(current.id());
    }

    private void deleteDiscussion() {
        int n = canvas.getPosts().size();
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete \"" + current.title() + "\" and its " + n + " post(s)?\nThis cannot be undone.",
                "Delete Discussion", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        String title = current.title();
        db.deleteDiscussion(current.id());
        reloadDiscussions(-1);
        setStatus("Deleted discussion \"" + title + "\".");
    }

    // ------------------------------------------------------------ post actions

    private void reload() {
        canvas.setData(new ArrayList<>(db.listPosts(current.id())), db.listLinks(current.id()));
        updateDetails();
        updateActions();
        updateStats();
    }

    private void newPostAtViewCenter() {
        Rectangle view = canvasScroll.getViewport().getViewRect();
        double z = canvas.getZoom();
        int x = (int) ((view.x + view.width / 2.0) / z) - GraphCanvas.NODE_W / 2;
        int y = (int) ((view.y + view.height / 2.0) / z) - GraphCanvas.NODE_H / 2;
        newPostRequested(x, y);
    }

    @Override
    public void newPostRequested(int x, int y) {
        PostDialog dlg = new PostDialog(this, "New Post", null, null);
        if (!dlg.showDialog()) return;
        Post p = newPost(dlg, x, y);
        db.insertPost(p);
        int id = p.id;
        pushUndo("Undo New Post", () -> db.deletePost(id));
        reload();
        canvas.select(canvas.findPost(id));
        setStatus("Added \"" + p.title + "\".");
    }

    private void reply(Post parent) {
        if (parent == null) return;
        PostDialog dlg = new PostDialog(this, "Respond to Post", null, parent.title);
        if (!dlg.showDialog()) return;
        Point spot = freeSpotBelow(parent);
        Post p = newPost(dlg, spot.x, spot.y);
        db.insertPost(p);
        db.insertLink(new Link(0, current.id(), p.id, parent.id, dlg.getLinkType()));
        int id = p.id;
        pushUndo("Undo Reply", () -> db.deletePost(id));   // link is removed by ON DELETE CASCADE
        reload();
        canvas.select(canvas.findPost(id));
        setStatus("Added response \"" + p.title + "\" (" + dlg.getLinkType().label.toLowerCase()
                + " \"" + parent.title + "\").");
    }

    private Post newPost(PostDialog dlg, int x, int y) {
        Post p = new Post();
        p.discussionId = current.id();
        p.author = dlg.getAuthor();
        p.title = dlg.getPostTitle();
        p.body = dlg.getBody();
        p.createdAt = Database.now();
        p.x = Math.max(0, x);
        p.y = Math.max(0, y);
        return p;
    }

    /** Finds a spot under the parent post that doesn't overlap an existing card. */
    private Point freeSpotBelow(Post parent) {
        int y = parent.y + GraphCanvas.NODE_H + 70;
        int[] offsets = {0, 220, -220, 440, -440, 660};
        for (int extraRow = 0; extraRow < 4; extraRow++) {
            for (int dx : offsets) {
                int x = parent.x + dx;
                if (x < 0) continue;
                Rectangle candidate = new Rectangle(x - 15, y + extraRow * 110 - 15,
                        GraphCanvas.NODE_W + 30, GraphCanvas.NODE_H + 30);
                boolean free = canvas.getPosts().stream().noneMatch(p ->
                        candidate.intersects(new Rectangle(p.x, p.y, GraphCanvas.NODE_W, GraphCanvas.NODE_H)));
                if (free) return new Point(x, y + extraRow * 110);
            }
        }
        return new Point(parent.x + 30, y);
    }

    @Override
    public void editRequested(Post p) { edit(p); }

    private void edit(Post p) {
        if (p == null) return;
        PostDialog dlg = new PostDialog(this, "Edit Post", p, null);
        if (!dlg.showDialog()) return;
        Post before = p.copy();
        p.author = dlg.getAuthor();
        p.title = dlg.getPostTitle();
        p.body = dlg.getBody();
        db.updatePost(p);
        pushUndo("Undo Edit", () -> db.updatePost(before));
        reload();
        setStatus("Saved changes to \"" + p.title + "\".");
    }

    private void deleteSelection() {
        if (canvas.isLinkMode()) { canvas.cancelLinkMode(); return; }
        Post p = canvas.getSelectedPost();
        Link l = canvas.getSelectedLink();
        if (p != null) {
            Post saved = p.copy();
            List<Link> savedLinks = canvas.getLinks().stream()
                    .filter(x -> x.fromPost() == p.id || x.toPost() == p.id).toList();
            db.deletePost(p.id);
            pushUndo("Undo Delete Post", () -> {
                db.insertPost(saved);
                for (Link x : savedLinks) db.insertLink(x);
            });
            canvas.select((Post) null);
            reload();
            setStatus("Deleted \"" + saved.title + "\"" + (savedLinks.isEmpty() ? "" :
                    " and " + savedLinks.size() + " connection(s)") + ". Press Ctrl+Z to undo.");
        } else if (l != null) {
            db.deleteLink(l.id());
            pushUndo("Undo Delete Link", () -> db.insertLink(l));
            canvas.select((Link) null);
            reload();
            setStatus("Deleted connection. Press Ctrl+Z to undo.");
        }
    }

    @Override
    public void postMoved(Post p, int oldX, int oldY) {
        db.movePost(p.id, p.x, p.y);
        int id = p.id;
        pushUndo("Undo Move", () -> db.movePost(id, oldX, oldY));
        setStatus("Moved \"" + p.title + "\".");
    }

    @Override
    public void linkRequested(Post from, Post to) {
        if (db.linkExists(from.id, to.id)) {
            setStatus("Those two posts are already connected.");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        LinkType[] types = LinkType.values();
        int choice = JOptionPane.showOptionDialog(this,
                "<html>How does <b>" + from.title + "</b> relate to <b>" + to.title + "</b>?</html>",
                "Connect Posts", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, types, types[0]);
        if (choice < 0) { setStatus("Link cancelled."); return; }
        Link created = db.insertLink(new Link(0, current.id(), from.id, to.id, types[choice]));
        pushUndo("Undo Link", () -> db.deleteLink(created.id()));
        reload();
        setStatus("Connected: \"" + from.title + "\" " + types[choice].label.toLowerCase()
                + " \"" + to.title + "\".");
    }

    @Override
    public void linkModeChanged(boolean active) {
        if (active) {
            setStatus("Click the post that \"" + canvas.getSelectedPost().title
                    + "\" responds to.  (Esc to cancel)");
        }
        updateActions();
    }

    @Override
    public void popupRequested(Post post, Link link, MouseEvent e, int worldX, int worldY) {
        JPopupMenu menu = new JPopupMenu();
        if (post != null) {
            menu.add(editAction);
            menu.add(replyAction);
            menu.add(linkAction);
            menu.addSeparator();
            menu.add(deleteAction);
        } else if (link != null) {
            menu.add(menuItem("Go to responding post", ev -> canvas.select(canvas.findPost(link.fromPost()))));
            menu.add(menuItem("Go to original post", ev -> canvas.select(canvas.findPost(link.toPost()))));
            menu.addSeparator();
            menu.add(deleteAction);
        } else {
            menu.add(menuItem("New Post Here", ev -> newPostRequested(worldX, worldY)));
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    // ----------------------------------------------------------------- undo

    private void pushUndo(String label, Runnable r) {
        undoStack.push(new UndoStep(label, r));
        if (undoStack.size() > 100) undoStack.removeLast();
        updateActions();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        UndoStep step = undoStack.pop();
        step.undo().run();
        reload();
        setStatus(step.label().replace("Undo ", "Undid: ") + ".");
    }

    // --------------------------------------------------------------- search

    private void searchChanged() {
        String q = searchField.getText();
        canvas.setSearch(q);
        if (q.isBlank()) setStatus("Search cleared.");
        else {
            int n = canvas.countMatches();
            setStatus(n == 0 ? "No posts match \"" + q.trim() + "\"."
                    : n + " post(s) match \"" + q.trim() + "\". Press Enter to jump between them.");
        }
    }

    private void nextMatch() {
        String q = searchField.getText().trim();
        if (q.isEmpty()) return;
        List<Post> matches = canvas.getPosts().stream().filter(p -> p.matches(q))
                .sorted((a, b) -> Integer.compare(a.id, b.id)).toList();
        if (matches.isEmpty()) { Toolkit.getDefaultToolkit().beep(); return; }
        Post sel = canvas.getSelectedPost();
        int idx = 0;
        if (sel != null) {
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i).id == sel.id) { idx = (i + 1) % matches.size(); break; }
            }
        }
        canvas.select(matches.get(idx));
        setStatus("Match " + (idx + 1) + " of " + matches.size() + ".");
    }

    // ------------------------------------------------------- details & state

    @Override
    public void selectionChanged() {
        updateDetails();
        updateActions();
    }

    private void updateDetails() {
        Post p = canvas.getSelectedPost();
        Link l = canvas.getSelectedLink();
        connModel.clear();
        detailButtons.removeAll();

        if (p != null) {
            detailTitle.setText(html(p.title));
            detailMeta.setText(p.author + "  ·  " + p.createdAt);
            detailBody.setText(p.body.isEmpty() ? "(no message text)" : p.body);
            for (Link x : canvas.getLinks()) {
                if (x.fromPost() == p.id) {
                    Post o = canvas.findPost(x.toPost());
                    connModel.addElement(new Connection(o, "→ " + x.type().label + ": " + o.title));
                } else if (x.toPost() == p.id) {
                    Post o = canvas.findPost(x.fromPost());
                    connModel.addElement(new Connection(o, "← " + o.title + " (" + x.type().label.toLowerCase() + " this)"));
                }
            }
            detailButtons.add(toolButton(replyAction));
            detailButtons.add(toolButton(editAction));
            detailButtons.add(toolButton(deleteAction));
        } else if (l != null) {
            Post from = canvas.findPost(l.fromPost()), to = canvas.findPost(l.toPost());
            detailTitle.setText("Connection");
            detailMeta.setText(l.type().label);
            detailBody.setText("\"" + from.title + "\" by " + from.author + "\n\n"
                    + l.type().label.toLowerCase() + "\n\n\"" + to.title + "\" by " + to.author);
            connModel.addElement(new Connection(from, "Responding post: " + from.title));
            connModel.addElement(new Connection(to, "Original post: " + to.title));
            detailButtons.add(toolButton(deleteAction));
        } else {
            detailTitle.setText("Nothing selected");
            detailMeta.setText(" ");
            detailBody.setText("Click a post to read it here.\n\n"
                    + "• Drag posts to arrange the discussion.\n"
                    + "• Double-click empty space to add a post.\n"
                    + "• Right-click a post for more options.\n"
                    + "• Use Search to highlight posts by keyword or author.");
        }
        detailBody.setCaretPosition(0);
        detailButtons.revalidate();
        detailButtons.repaint();
    }

    private static String html(String s) {
        return "<html>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</html>";
    }

    private void updateActions() {
        boolean post = canvas.getSelectedPost() != null;
        boolean any = post || canvas.getSelectedLink() != null;
        replyAction.setEnabled(post);
        linkAction.setEnabled(post && !canvas.isLinkMode());
        editAction.setEnabled(post);
        deleteAction.setEnabled(any);
        undoAction.setEnabled(!undoStack.isEmpty());
        undoAction.putValue(Action.NAME, undoStack.isEmpty() ? "Undo" : undoStack.peek().label());
    }

    private void updateStats() {
        stats.setText(canvas.getPosts().size() + " posts  ·  " + canvas.getLinks().size()
                + " connections  ·  Zoom " + Math.round(canvas.getZoom() * 100) + "%");
    }

    private void zoomBy(double factor) {
        canvas.setZoom(canvas.getZoom() * factor);
        updateStats();
    }

    private void setStatus(String s) { status.setText(s); }

    private void showGuide() {
        String guide = """
            <html><body style='width:420px; font-family:sans-serif'>
            <h2>DigEdit Quick Guide</h2>
            <p>A discussion is shown as a graph: each <b>card</b> is a post and each <b>arrow</b>
            points from a response to the post it responds to.</p>
            <h3>Basics</h3>
            <ul>
              <li><b>Select</b> a post or arrow by clicking it. Its details appear on the right.</li>
              <li><b>Move</b> a post by dragging it. Positions are saved automatically.</li>
              <li><b>Add</b> a post: double-click empty space, or press Ctrl+N.</li>
              <li><b>Reply</b>: select a post and press Reply (Ctrl+R).</li>
              <li><b>Link</b> two existing posts: select one, press Link (Ctrl+L), then click the other.</li>
              <li><b>Edit</b>: double-click a post (Ctrl+E).</li>
              <li><b>Delete</b>: select and press Delete. Every change can be undone with Ctrl+Z.</li>
            </ul>
            <h3>Finding things</h3>
            <ul>
              <li>Type in <b>Search</b> to highlight matching posts; others fade out. Enter jumps between matches.</li>
              <li>Double-click a row under <b>Connections</b> to jump to that post.</li>
            </ul>
            <h3>Viewing</h3>
            <ul>
              <li>Zoom with Ctrl + mouse wheel, Ctrl+= and Ctrl+-, or the + / – buttons.</li>
              <li>View → High Contrast for a dark, high-contrast canvas.</li>
              <li>Link types differ by colour <i>and</i> line style, so they are readable without colour.</li>
            </ul>
            </body></html>""";
        JOptionPane.showMessageDialog(this, new JLabel(guide), "Quick Guide", JOptionPane.PLAIN_MESSAGE);
    }
}
