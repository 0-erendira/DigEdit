package digedit;

import digedit.Model.Link;
import digedit.Model.LinkType;
import digedit.Model.Post;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The visual discussion graph. Posts are drawn as cards; links are drawn as
 * arrows from the responding post to the post it responds to.
 *
 * Interaction (direct manipulation):
 *   click        select a post or link
 *   drag         move a post (saved to the database on release)
 *   double-click edit a post, or create a new post on empty space
 *   right-click  context menu
 *   Ctrl+wheel   zoom
 */
public class GraphCanvas extends JPanel {

    /** Callbacks to the main window, which owns the database and undo history. */
    public interface Listener {
        void selectionChanged();
        void postMoved(Post p, int oldX, int oldY);
        void linkRequested(Post from, Post to);
        void editRequested(Post p);
        void newPostRequested(int x, int y);
        void popupRequested(Post post, Link link, MouseEvent e, int worldX, int worldY);
        void linkModeChanged(boolean active);
    }

    public static final int NODE_W = 210;
    public static final int NODE_H = 66;

    private final Listener listener;
    private List<Post> posts = new ArrayList<>();
    private List<Link> links = new ArrayList<>();

    private Post selectedPost;
    private Link selectedLink;
    private String search = "";
    private double zoom = 1.0;
    private boolean highContrast = false;

    // dragging state
    private Post dragging;
    private int dragOffX, dragOffY, dragStartX, dragStartY;

    // link mode: the post we are linking FROM, and the current mouse position
    private Post linkSource;
    private Point linkMouse;

    public GraphCanvas(Listener listener) {
        this.listener = listener;
        setFocusable(true);
        setToolTipText("");
        MouseAdapter mouse = new MouseHandler();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(e -> {
            if (e.isControlDown() || e.isMetaDown()) {
                setZoom(zoom * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1));
            } else {
                Component sp = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
                if (sp != null) sp.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, sp));
            }
        });
    }

    // ------------------------------------------------------------- data in

    public void setData(List<Post> posts, List<Link> links) {
        this.posts = posts;
        this.links = links;
        if (selectedPost != null) selectedPost = findPost(selectedPost.id);
        if (selectedLink != null && links.stream().noneMatch(l -> l.id() == selectedLink.id())) selectedLink = null;
        revalidate();
        repaint();
    }

    public List<Post> getPosts() { return posts; }
    public List<Link> getLinks() { return links; }

    public Post findPost(int id) {
        for (Post p : posts) if (p.id == id) return p;
        return null;
    }

    public Post getSelectedPost() { return selectedPost; }
    public Link getSelectedLink() { return selectedLink; }

    public void select(Post p) {
        selectedPost = p;
        selectedLink = null;
        if (p != null) scrollRectToVisible(toScreen(nodeRect(p)));
        listener.selectionChanged();
        repaint();
    }

    public void select(Link l) {
        selectedLink = l;
        selectedPost = null;
        listener.selectionChanged();
        repaint();
    }

    public void setSearch(String s) {
        search = s == null ? "" : s.trim();
        repaint();
    }

    public int countMatches() {
        if (search.isEmpty()) return 0;
        return (int) posts.stream().filter(p -> p.matches(search)).count();
    }

    public double getZoom() { return zoom; }

    public void setZoom(double z) {
        zoom = Math.max(0.5, Math.min(2.5, z));
        revalidate();
        repaint();
    }

    public void setHighContrast(boolean on) {
        highContrast = on;
        repaint();
    }

    // ------------------------------------------------------------ link mode

    public boolean isLinkMode() { return linkSource != null; }

    public void startLinkMode(Post from) {
        linkSource = from;
        linkMouse = null;
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        listener.linkModeChanged(true);
        repaint();
    }

    public void cancelLinkMode() {
        if (linkSource == null) return;
        linkSource = null;
        setCursor(Cursor.getDefaultCursor());
        listener.linkModeChanged(false);
        repaint();
    }

    // ------------------------------------------------------------- geometry

    private static Rectangle nodeRect(Post p) {
        return new Rectangle(p.x, p.y, NODE_W, NODE_H);
    }

    private Rectangle toScreen(Rectangle r) {
        return new Rectangle((int) (r.x * zoom), (int) (r.y * zoom),
                (int) (r.width * zoom), (int) (r.height * zoom));
    }

    private Point toWorld(Point screen) {
        return new Point((int) (screen.x / zoom), (int) (screen.y / zoom));
    }

    private Post postAt(Point world) {
        for (int i = posts.size() - 1; i >= 0; i--) {   // topmost first
            if (nodeRect(posts.get(i)).contains(world)) return posts.get(i);
        }
        return null;
    }

    private Link linkAt(Point world) {
        for (Link l : links) {
            Line2D seg = linkSegment(l);
            if (seg != null && seg.ptSegDist(world) < 6) return l;
        }
        return null;
    }

    /** Line from the edge of the "from" card to the edge of the "to" card. */
    private Line2D linkSegment(Link l) {
        Post from = findPost(l.fromPost()), to = findPost(l.toPost());
        if (from == null || to == null) return null;
        Point2D a = center(from), b = center(to);
        return new Line2D.Double(edgePoint(from, b), edgePoint(to, a));
    }

    private static Point2D center(Post p) {
        return new Point2D.Double(p.x + NODE_W / 2.0, p.y + NODE_H / 2.0);
    }

    /** Where the line from this card's center toward `toward` crosses the card's border. */
    private static Point2D edgePoint(Post p, Point2D toward) {
        Point2D c = center(p);
        double dx = toward.getX() - c.getX(), dy = toward.getY() - c.getY();
        if (dx == 0 && dy == 0) return c;
        double sx = dx == 0 ? Double.MAX_VALUE : (NODE_W / 2.0) / Math.abs(dx);
        double sy = dy == 0 ? Double.MAX_VALUE : (NODE_H / 2.0) / Math.abs(dy);
        double s = Math.min(sx, sy);
        return new Point2D.Double(c.getX() + dx * s, c.getY() + dy * s);
    }

    @Override
    public Dimension getPreferredSize() {
        int maxX = 600, maxY = 400;
        for (Post p : posts) {
            maxX = Math.max(maxX, p.x + NODE_W + 80);
            maxY = Math.max(maxY, p.y + NODE_H + 80);
        }
        return new Dimension((int) (maxX * zoom), (int) (maxY * zoom));
    }

    // -------------------------------------------------------------- colours

    private Color bg()         { return highContrast ? Color.BLACK : new Color(0xF7F7F9); }
    private Color grid()       { return highContrast ? new Color(0x222222) : new Color(0xE6E6EC); }
    private Color card()       { return highContrast ? Color.BLACK : Color.WHITE; }
    private Color cardText()   { return highContrast ? Color.WHITE : new Color(0x1E1E24); }
    private Color subText()    { return highContrast ? new Color(0xFFFF66) : new Color(0x6B6B76); }
    private Color border()     { return highContrast ? Color.WHITE : new Color(0xB8B8C4); }
    private Color selection()  { return highContrast ? new Color(0x00FFFF) : new Color(0x2B6CEB); }
    private Color matchFill()  { return highContrast ? new Color(0x444400) : new Color(0xFFF3B0); }

    /**
     * Link colours use the Okabe-Ito colour-blind-safe palette, and every link
     * type also has its own line style, so colour is never the only cue.
     */
    public static Color linkColor(LinkType t, boolean highContrast) {
        return switch (t) {
            case REPLY -> highContrast ? Color.WHITE : new Color(0x555560);
            case AGREE -> highContrast ? new Color(0x56B4E9) : new Color(0x0072B2);
            case DISAGREE -> highContrast ? new Color(0xFFA040) : new Color(0xD55E00);
        };
    }

    public static Stroke linkStroke(LinkType t, float width) {
        return switch (t) {
            case REPLY -> new BasicStroke(width);
            case AGREE -> new BasicStroke(width + 1);
            case DISAGREE -> new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{9f, 6f}, 0f);
        };
    }

    // ------------------------------------------------------------- painting

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(bg());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.scale(zoom, zoom);

        // background grid
        g.setColor(grid());
        int w = (int) (getWidth() / zoom), h = (int) (getHeight() / zoom);
        for (int x = 0; x < w; x += 40) g.drawLine(x, 0, x, h);
        for (int y = 0; y < h; y += 40) g.drawLine(0, y, w, y);

        for (Link l : links) paintLink(g, l);
        for (Post p : posts) paintPost(g, p);

        // rubber-band line while choosing a link target
        if (linkSource != null && linkMouse != null) {
            g.setColor(selection());
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    10f, new float[]{4f, 6f}, 0f));
            Point2D c = center(linkSource);
            g.draw(new Line2D.Double(c, linkMouse));
        }

        if (posts.isEmpty()) {
            g.setColor(subText());
            g.setFont(getFont().deriveFont(15f));
            g.drawString("This discussion is empty. Double-click anywhere to add the first post.", 60, 80);
        }
        g.dispose();
    }

    private void paintLink(Graphics2D g, Link l) {
        Line2D seg = linkSegment(l);
        if (seg == null) return;
        boolean sel = selectedLink != null && selectedLink.id() == l.id();
        boolean dim = !search.isEmpty() && !(findPost(l.fromPost()).matches(search)
                && findPost(l.toPost()).matches(search));

        Color c = linkColor(l.type(), highContrast);
        if (dim) c = new Color(c.getRed(), c.getGreen(), c.getBlue(), 60);
        if (sel) {
            g.setColor(new Color(selection().getRed(), selection().getGreen(), selection().getBlue(), 90));
            g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(seg);
        }
        g.setColor(c);
        g.setStroke(linkStroke(l.type(), 2f));
        g.draw(seg);

        // arrowhead pointing at the post being responded to
        double angle = Math.atan2(seg.getY2() - seg.getY1(), seg.getX2() - seg.getX1());
        Path2D head = new Path2D.Double();
        head.moveTo(seg.getX2(), seg.getY2());
        head.lineTo(seg.getX2() - 12 * Math.cos(angle - 0.4), seg.getY2() - 12 * Math.sin(angle - 0.4));
        head.lineTo(seg.getX2() - 12 * Math.cos(angle + 0.4), seg.getY2() - 12 * Math.sin(angle + 0.4));
        head.closePath();
        g.fill(head);
    }

    private void paintPost(Graphics2D g, Post p) {
        Rectangle r = nodeRect(p);
        boolean sel = selectedPost != null && selectedPost.id == p.id;
        boolean searching = !search.isEmpty();
        boolean match = searching && p.matches(search);

        Composite old = g.getComposite();
        if (searching && !match) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));

        // shadow
        g.setColor(new Color(0, 0, 0, 25));
        g.fillRoundRect(r.x + 3, r.y + 3, r.width, r.height, 14, 14);
        // card
        g.setColor(match ? matchFill() : card());
        g.fillRoundRect(r.x, r.y, r.width, r.height, 14, 14);
        g.setColor(sel ? selection() : border());
        g.setStroke(new BasicStroke(sel ? 3f : 1.2f));
        g.drawRoundRect(r.x, r.y, r.width, r.height, 14, 14);
        if (linkSource != null && linkSource.id == p.id) {
            g.setColor(selection());
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{5f, 4f}, 0f));
            g.drawRoundRect(r.x - 4, r.y - 4, r.width + 8, r.height + 8, 18, 18);
        }

        // text
        Font base = getFont();
        g.setFont(base.deriveFont(Font.BOLD, 13f));
        g.setColor(cardText());
        g.drawString(ellipsize(g, p.title, r.width - 20), r.x + 10, r.y + 22);
        g.setFont(base.deriveFont(11.5f));
        g.setColor(subText());
        g.drawString(ellipsize(g, p.author, r.width - 20), r.x + 10, r.y + 40);
        g.setColor(cardText());
        g.drawString(ellipsize(g, p.body.replace('\n', ' '), r.width - 20), r.x + 10, r.y + 56);

        g.setComposite(old);
    }

    private static String ellipsize(Graphics2D g, String s, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxWidth) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > maxWidth) end--;
        return s.substring(0, end) + dots;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Point w = toWorld(e.getPoint());
        Post p = postAt(w);
        if (p != null) {
            return "<html><b>" + esc(p.title) + "</b><br>" + esc(p.author)
                    + "<br><br>Double-click to edit · Right-click for options</html>";
        }
        Link l = linkAt(w);
        if (l != null) {
            Post from = findPost(l.fromPost()), to = findPost(l.toPost());
            return "<html>\"" + esc(from.title) + "\"<br><i>" + l.type().label.toLowerCase()
                    + "</i><br>\"" + esc(to.title) + "\"</html>";
        }
        return null;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- mouse

    private class MouseHandler extends MouseAdapter {
        @Override public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            Point w = toWorld(e.getPoint());
            Post hit = postAt(w);

            if (e.isPopupTrigger()) { popup(e, w); return; }
            if (!SwingUtilities.isLeftMouseButton(e)) return;

            if (linkSource != null) {               // finishing a link
                Post source = linkSource;
                cancelLinkMode();
                if (hit != null && hit.id != source.id) listener.linkRequested(source, hit);
                return;
            }

            if (hit != null) {
                if (e.getClickCount() == 2) { listener.editRequested(hit); return; }
                select(hit);
                dragging = hit;
                dragOffX = w.x - hit.x; dragOffY = w.y - hit.y;
                dragStartX = hit.x; dragStartY = hit.y;
                // bring to front
                posts.remove(hit);
                posts.add(hit);
            } else {
                Link l = linkAt(w);
                if (l != null) { select(l); return; }
                if (e.getClickCount() == 2) {
                    listener.newPostRequested(w.x - NODE_W / 2, w.y - NODE_H / 2);
                } else {
                    select((Post) null);
                }
            }
        }

        @Override public void mouseDragged(MouseEvent e) {
            if (dragging == null) return;
            Point w = toWorld(e.getPoint());
            dragging.x = Math.max(0, w.x - dragOffX);
            dragging.y = Math.max(0, w.y - dragOffY);
            revalidate();
            repaint();
        }

        @Override public void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) { popup(e, toWorld(e.getPoint())); return; }
            if (dragging != null && (dragging.x != dragStartX || dragging.y != dragStartY)) {
                listener.postMoved(dragging, dragStartX, dragStartY);
            }
            dragging = null;
        }

        @Override public void mouseMoved(MouseEvent e) {
            if (linkSource != null) {
                linkMouse = toWorld(e.getPoint());
                repaint();
            }
        }

        private void popup(MouseEvent e, Point w) {
            cancelLinkMode();
            Post p = postAt(w);
            Link l = p == null ? linkAt(w) : null;
            if (p != null) select(p);
            else if (l != null) select(l);
            listener.popupRequested(p, l, e, w.x, w.y);
        }
    }
}
