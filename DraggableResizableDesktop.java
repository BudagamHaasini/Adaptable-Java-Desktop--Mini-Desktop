import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.util.*;
import java.util.List;
import java.io.*;
import javax.imageio.ImageIO;
import java.text.SimpleDateFormat;

/**
 * DraggableResizableDesktop - single-file complete program (Final)
 *
 * - Password prompt FIRST (no UI created until password & mode chosen)
 * - Mode selection after password (Kids / Student)
 * - Kids: Chrome --app mode (no tabs/address bar)
 * - Student: Chrome normal window (tabs allowed) launched with isolated user-data-dir
 * - Use unique user-data-dir per browser launch so Chrome opens the exact URL
 * - Hides taskbar while launched browser process is running and restores when it exits
 * - An ✖ exit button appears to let parent/child close the launched browser process
 * - NEW: Auto work/break cycle per app (when enabled): X minutes work → X minutes locked → repeat
 *
 * Notes:
 * - Intended for Windows (PowerShell used to hide/show taskbar).
 * - Keep images folder paths, etc. as in your project.
 *
 * Save as: DraggableResizableDesktop.java
 */
public class DraggableResizableDesktop extends JFrame {

    static String parentPassword = "1234";
    static boolean authenticated = false;

    boolean allowProgrammaticMinimize = false;
    Map<String, Long> recentUsage = new LinkedHashMap<>();
    Map<String, Long> recentStartTime = new LinkedHashMap<>();

    // track active browser process started by this app
    private volatile Process activeBrowserProcess = null;
    private JButton exitBrowserBtn;

    enum Mode { KIDS, STUDENT }
    private Mode currentMode = Mode.KIDS; // default; set at login selection via constructor

    static class App {
        String name, url, iconPath;
        int allowedMinutes = -1;
        int usedSeconds = 0;
        boolean isBlocked = false;
        boolean autoUnblock = false;
        boolean autoResetExtend = false; // whether cycle is enabled
        int autoResetMinutes = -1;       // the X minutes for cycle
        long sessionStartTime = 0;
        int iconWidth = 64;
        int iconHeight = 64;
        javax.swing.Timer timer;         // usage timer
        javax.swing.Timer cycleTimer;    // cycle timer for auto work/break
        int posX = -1, posY = -1;

        App(String name, String url, String iconPath) {
            this.name = name;
            this.url = url;
            this.iconPath = iconPath;
        }

        void stopCycleTimer() {
            if (cycleTimer != null) {
                cycleTimer.stop();
                cycleTimer = null;
            }
        }
    }

    static java.util.List<App> APPS = new ArrayList<>(Arrays.asList(
            new App("YouTube", "https://www.youtube.com", "images/youtube.png"),
            new App("Facebook", "https://www.facebook.com", "images/facebook.png"),
            new App("Instagram", "https://www.instagram.com", "images/instagram.png"),
            new App("Twitter", "https://twitter.com", "images/twitter.png"),
            new App("Snapchat", "https://www.snapchat.com", "images/snapchat.png"),
            new App("WhatsApp", "https://web.whatsapp.com", "images/whatsapp.png"),
            new App("Word", "word", "images/word.png"),
            new App("Excel", "excel", "images/excel.png")
    ));

    static java.util.List<App> RECYCLE_BIN = new ArrayList<>();
    JLabel background;
    JLayeredPane layeredPane;
    JButton recycleBinBtn;
    String wallpaperPath = "images/bg.jpg";

    /**
     * Primary constructor. Frame will only be created after password+mode are chosen
     */
    public DraggableResizableDesktop(Mode mode) {
        super("Virtual Desktop");
        this.currentMode = mode;

        setUndecorated(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLayout(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setResizable(false);

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                if (checkPassword("Enter Parent Password to Exit")) System.exit(0);
                else JOptionPane.showMessageDialog(DraggableResizableDesktop.this, "Wrong Password!");
            }
        });

        layeredPane = new JLayeredPane();
        layeredPane.setLayout(null);
        setContentPane(layeredPane);

        background = new JLabel();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        background.setBounds(0, 0, screen.width, screen.height);
        setWallpaper(wallpaperPath);
        layeredPane.add(background, Integer.valueOf(0));

        createExitBrowserButton();

        addDesktopRightClickMenu();
        refreshIcons();
        setupParentControlButton();

        packFrameToFullScreen();
    }

    // ---------- UI creation helpers ----------
    private void createExitBrowserButton() {
        exitBrowserBtn = new JButton("✖");
        exitBrowserBtn.setFont(new Font("Arial", Font.BOLD, 20));
        exitBrowserBtn.setForeground(Color.WHITE);
        exitBrowserBtn.setBackground(Color.RED);
        exitBrowserBtn.setFocusPainted(false);
        exitBrowserBtn.setBorderPainted(false);
        exitBrowserBtn.setVisible(false);  // hidden until a browser app opens

        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        exitBrowserBtn.setBounds(d.width - 90, 20, 60, 40);
        exitBrowserBtn.addActionListener(e -> {
            // Only parent should forcibly close; ask password
            if (checkPassword("Enter Parent Password to Close Browser")) {
                closeActiveBrowserProcess();
                showTaskbarWindows();
                exitBrowserBtn.setVisible(false);
            } else {
                JOptionPane.showMessageDialog(this, "Wrong Password!");
            }
        });

        layeredPane.add(exitBrowserBtn, Integer.valueOf(3));

        this.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                Dimension s = getSize();
                exitBrowserBtn.setBounds(s.width - 90, 20, 60, 40);
            }
        });
    }

    private void packFrameToFullScreen() {
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(d);
        setLocation(0, 0);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
    }

    /**
     * Prompt for password and mode BEFORE creating the desktop.
     * Returns chosen Mode if password correct, otherwise null.
     */
    public static Mode promptPasswordAndChooseMode() {
        // Password prompt
        JPasswordField pf = new JPasswordField();
        int ok = JOptionPane.showConfirmDialog(null, pf, "Enter Parent Password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return null;
        String pwd = new String(pf.getPassword());
        if (!pwd.equals(parentPassword)) {
            JOptionPane.showMessageDialog(null, "Wrong Password!");
            return null;
        }

        // Mode selection
        String[] modes = {"Kids Mode (strict)", "Student Mode (semi-restricted)"};
        String sel = (String) JOptionPane.showInputDialog(null, "Choose mode to start:",
                "Select Mode", JOptionPane.PLAIN_MESSAGE, null, modes, modes[1]);
        if (sel == null) return null;
        if (sel.startsWith("Kids")) return Mode.KIDS;
        else return Mode.STUDENT;
    }

    static boolean checkPassword(String message) {
        JPasswordField pf = new JPasswordField();
        int ok = JOptionPane.showConfirmDialog(null, pf, message, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return ok == JOptionPane.OK_OPTION && new String(pf.getPassword()).equals(parentPassword);
    }

    private void setWallpaper(String wallpaperPath2) {
        try {
            Image img = ImageIO.read(new File(wallpaperPath2));
            int w = (background.getWidth() > 0) ? background.getWidth() : getWidth();
            int h = (background.getHeight() > 0) ? background.getHeight() : getHeight();
            Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            background.setIcon(new ImageIcon(scaled));
            background.setText(null);
        } catch (Exception ex) {
            background.setIcon(null);
            background.setText("Wallpaper not found");
            background.setHorizontalAlignment(SwingConstants.CENTER);
            background.setForeground(Color.WHITE);
            background.setFont(background.getFont().deriveFont(24f));
        }
    }

    // ---------- app time-limit handling ----------
    private void closeAppAutomatically(App app) {
        if (app == null) return;
        if (app.timer != null) {
            app.timer.stop();
            app.timer = null;
        }

        recentUsage.put(app.name, (long) app.usedSeconds);
        app.isBlocked = true;

        // If cycle mode is enabled, keep the cycleTimer running (it will toggle block/unblock).
        // Otherwise, if autoUnblock is set (simple), schedule unblock
        if (!app.autoResetExtend && app.autoUnblock && app.allowedMinutes > 0) {
            int delayMillis = app.allowedMinutes * 60 * 1000;
            javax.swing.Timer autoUnblockTimer = new javax.swing.Timer(delayMillis, ev -> {
                app.isBlocked = false;
                JOptionPane.showMessageDialog(this,
                        app.name + " is available again (auto-unblocked).");
                ((javax.swing.Timer) ev.getSource()).stop();
            });
            autoUnblockTimer.setRepeats(false);
            autoUnblockTimer.start();
        }

        // If cycle was enabled but cycleTimer is not set (edge-case), set it up now:
        if (app.autoResetExtend && app.autoResetMinutes > 0 && app.cycleTimer == null) {
            startCycleTimerForApp(app);
        }

        try {
            String u = app.url.toLowerCase();
            if (u.contains("word")) Runtime.getRuntime().exec("cmd /c taskkill /IM winword.exe /F");
            else if (u.contains("excel")) Runtime.getRuntime().exec("cmd /c taskkill /IM excel.exe /F");
            else if (u.contains("powerpnt")) Runtime.getRuntime().exec("cmd /c taskkill /IM powerpnt.exe /F");
            else if (u.contains("chrome") || u.contains("http")) {
                Runtime.getRuntime().exec("cmd /c taskkill /IM chrome.exe /F");
                Runtime.getRuntime().exec("cmd /c taskkill /IM msedge.exe /F");
                Runtime.getRuntime().exec("cmd /c taskkill /IM firefox.exe /F");
            } else if (u.contains("notepad")) Runtime.getRuntime().exec("cmd /c taskkill /IM notepad.exe /F");
            else if (u.endsWith(".exe")) Runtime.getRuntime().exec("taskkill /IM " + new File(u).getName() + " /F");
        } catch (Exception ignored) {}

        JOptionPane.showMessageDialog(this,
                app.name + " has reached its time limit and has been automatically closed.",
                "Time Limit Reached",
                JOptionPane.WARNING_MESSAGE);
    }

    // ---------- desktop right-click menu ----------
    private void addDesktopRightClickMenu() {
        background.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { showMenuIfRightClick(e); }
            public void mouseReleased(MouseEvent e) { showMenuIfRightClick(e); }

            private void showMenuIfRightClick(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu menu = new JPopupMenu();

                    JMenuItem allApps = new JMenuItem("All Apps");
                    allApps.addActionListener(a -> showAllAppsList());

                    JMenuItem recent = new JMenuItem("Recently Used");
                    recent.addActionListener(a -> showRecentApps());

                    JMenuItem addApp = new JMenuItem("Add New App");
                    addApp.addActionListener(a -> addNewApp());

                    JMenuItem changeWallpaper = new JMenuItem("Change Wallpaper");
                    changeWallpaper.addActionListener(a -> changeWallpaper());

                    JMenuItem changePwd = new JMenuItem("Change Parent Password");
                    changePwd.addActionListener(a -> changeParentPassword());

                    JMenuItem exitDesktop = new JMenuItem("Exit Desktop");
                    exitDesktop.addActionListener(a -> {
                        if (checkPassword("Enter Parent Password to Exit")) System.exit(0);
                        else JOptionPane.showMessageDialog(DraggableResizableDesktop.this, "Wrong Password!");
                    });

                    menu.add(allApps);
                    menu.add(recent);
                    menu.add(addApp);
                    menu.addSeparator();
                    menu.add(changeWallpaper);
                    menu.add(changePwd);
                    menu.addSeparator();
                    menu.add(exitDesktop);

                    menu.show(background, e.getX(), e.getY());
                }
            }
        });
    }

    private void addNewApp() {
        if (!checkPassword("Enter Parent Password to Add App")) {
            JOptionPane.showMessageDialog(this, "Wrong Password!");
            return;
        }

        String name = JOptionPane.showInputDialog(this, "Enter App Name (e.g., Spotify, Word, Chrome):");
        if (name == null || name.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "App name cannot be empty!");
            return;
        }
        name = name.trim();

        String url = "";
        String iconPath = "images/default.png";

        int res = JOptionPane.showConfirmDialog(this, "Do you want to select the app executable (.exe/.lnk)?", "Select Executable", JOptionPane.YES_NO_OPTION);
        if (res == JOptionPane.YES_OPTION) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select App (.exe or .lnk)");
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File exeFile = chooser.getSelectedFile();
                url = exeFile.getAbsolutePath();
                iconPath = url; // optional: use exe icon if available
            } else {
                JOptionPane.showMessageDialog(this, "No file selected!");
                return;
            }
        } else {
            // fallback: web URL
            url = JOptionPane.showInputDialog(this, "Enter App URL (e.g., https://www.spotify.com):");
            if (url == null || url.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "App URL cannot be empty!");
                return;
            }
            url = url.trim();
            iconPath = "images/browser.png";
        }

        App newApp = new App(name, url, iconPath);
        APPS.add(newApp);
        JOptionPane.showMessageDialog(this, name + " added successfully!");
        refreshIcons();
    }

    private void showAllAppsList() {
        String[] names = APPS.stream().map(a -> a.name).toArray(String[]::new);
        String choice = (String) JOptionPane.showInputDialog(this, "Select an app to open:",
                "All Apps", JOptionPane.PLAIN_MESSAGE, null, names, names.length > 0 ? names[0] : null);
        if (choice != null) {
            App selected = APPS.stream().filter(a -> a.name.equals(choice)).findFirst().orElse(null);
            if (selected != null) openApp(selected);
        }
    }

    private void showRecentApps() {
        if (recentUsage.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No apps used yet.");
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a");
        StringBuilder sb = new StringBuilder("Recently Used Apps:\n");
        for (String appName : recentUsage.keySet()) {
            long seconds = recentUsage.get(appName);
            Long startMillis = recentStartTime.get(appName);
            String startTimeStr = (startMillis != null) ? sdf.format(new Date(startMillis)) : "N/A";
            sb.append("• ").append(appName)
                    .append(" — ").append(seconds).append(" seconds")
                    .append(" (Started at ").append(startTimeStr).append(")\n");
        }
        JOptionPane.showMessageDialog(this, sb.toString());
    }

    private void changeWallpaper() {
        if (!checkPassword("Enter Parent Password to Change Wallpaper")) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Wallpaper");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            wallpaperPath = chooser.getSelectedFile().getAbsolutePath();
            setWallpaper(wallpaperPath);
        }
    }

    private void changeParentPassword() {
        if (!checkPassword("Enter Current Parent Password")) {
            JOptionPane.showMessageDialog(this, "Wrong Password!");
            return;
        }
        JPasswordField pf = new JPasswordField();
        int ok = JOptionPane.showConfirmDialog(this, pf, "Enter New Password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) {
            parentPassword = new String(pf.getPassword());
            JOptionPane.showMessageDialog(this, "Password changed successfully!");
        }
    }

    private void setupParentControlButton() {
        JButton parentBtn = new JButton("Parent Controls");
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        int bx = Math.max(20, d.width - 220);
        parentBtn.setBounds(bx, 20, 180, 40);
        parentBtn.addActionListener(e -> openParentControls());
        layeredPane.add(parentBtn, Integer.valueOf(2));
    }

    private void refreshIcons() {
        layeredPane.removeAll();
        layeredPane.add(background, Integer.valueOf(0));
        // re-add exit button layer on top after clearing
        layeredPane.add(exitBrowserBtn, Integer.valueOf(3));
        setupParentControlButton();

        int x = 60, y = 100;
        int col = 4;
        int spacingX = 200, spacingY = 150;

        for (int i = 0; i < APPS.size(); i++) {
            App app = APPS.get(i);
            JButton btn = new JButton(app.name);
            btn.setVerticalTextPosition(SwingConstants.BOTTOM);
            btn.setHorizontalTextPosition(SwingConstants.CENTER);
            btn.setContentAreaFilled(false);
            btn.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));

            try {
                Image img = ImageIO.read(new File(app.iconPath));
                btn.setIcon(new ImageIcon(img.getScaledInstance(app.iconWidth, app.iconHeight, Image.SCALE_SMOOTH)));
            } catch (Exception ignored) {}

            int posX = (app.posX == -1) ? x + (i % col) * spacingX : app.posX;
            int posY = (app.posY == -1) ? y + (i / col) * spacingY : app.posY;
            btn.setBounds(posX, posY, 100, 100);

            btn.addMouseListener(new MouseAdapter() {
                private boolean dragging = false;
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) showAppContextMenu(btn, app, e);
                    else if (e.getClickCount() == 2 && !dragging) openApp(app);
                }
            });

            makeDraggable(btn, app);
            layeredPane.add(btn, Integer.valueOf(1));
        }

        recycleBinBtn = new JButton("Recycle Bin (" + RECYCLE_BIN.size() + ")");
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        recycleBinBtn.setBounds(Math.max(20, scr.width - 200), Math.max(200, scr.height - 200), 130, 60);
        recycleBinBtn.addActionListener(e -> openRecycleBin());
        layeredPane.add(recycleBinBtn, Integer.valueOf(1));

        // ensure exitBrowserBtn and parent button are above icons
        layeredPane.add(exitBrowserBtn, Integer.valueOf(3));

        layeredPane.revalidate();
        layeredPane.repaint();
    }

    private void makeDraggable(JButton btn, App app) {
        final Point offset = new Point();
        final boolean[] dragging = {false};
        btn.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { offset.setLocation(e.getPoint()); }
            public void mouseReleased(MouseEvent e) {
                if (dragging[0]) {
                    Rectangle icon = btn.getBounds();
                    Rectangle bin = recycleBinBtn.getBounds();
                    if (bin.intersects(icon)) {
                        if (checkPassword("Enter Password to Move App to Recycle Bin")) {
                            app.stopCycleTimer();
                            APPS.remove(app);
                            RECYCLE_BIN.add(app);
                            refreshIcons();
                        }
                    } else {
                        app.posX = btn.getX();
                        app.posY = btn.getY();
                    }
                    dragging[0] = false;
                }
            }
        });
        btn.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                dragging[0] = true;
                int newX = btn.getX() + e.getX() - offset.x;
                int newY = btn.getY() + e.getY() - offset.y;
                btn.setLocation(newX, newY);
            }
        });
    }

    private void showAppContextMenu(JButton btn, App app, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem rename = new JMenuItem("Rename");
        rename.addActionListener(a -> {
            String newName = JOptionPane.showInputDialog(this, "Enter new name:", app.name);
            if (newName != null && !newName.trim().isEmpty()) {
                app.name = newName.trim();
                refreshIcons();
            }
        });

        JMenuItem resize = new JMenuItem("Resize Icon");
        resize.addActionListener(a -> {
            String sizeStr = JOptionPane.showInputDialog(this, "Enter size (e.g., 64):", app.iconWidth);
            if (sizeStr != null) {
                try { int size = Integer.parseInt(sizeStr.trim()); app.iconWidth = app.iconHeight = size; refreshIcons(); }
                catch (Exception ignored) {}
            }
        });

        menu.add(rename);
        menu.add(resize);
        menu.show(btn, e.getX(), e.getY());
    }

    /**
     * Launch browser apps with taskbar hide + different behavior per mode:
     * - KIDS: chrome --app (no tabs/address bar)
     * - STUDENT: chrome normal window (tabs allowed) but still launches via isolated user-data-dir
     *
     * Important fix: use a unique user-data-dir per launch (timestamp appended) so Chrome opens the exact URL.
     */
    private void launchBrowserAppWithRestrictions(App app) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");

        try {
            if (windows) {
                hideTaskbarWindows();
            }

            // Check common Chrome install locations (64-bit then 32-bit)
            String[] possibleChromePaths = new String[] {
                    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
            };

            String chromePath = null;
            for (String p : possibleChromePaths) {
                if (new File(p).exists()) { chromePath = p; break; }
            }

            if (chromePath != null) {
                // Make user-data-dir unique per launch to force a new Chrome instance to open the URL.
                String safeName = app.name.replaceAll("[^a-zA-Z0-9\\-_]", "_");
                String userDataDir = "C:\\KidModeProfiles\\" + safeName + "_" + System.currentTimeMillis();
                File ud = new File(userDataDir);
                if (!ud.exists()) ud.mkdirs();

                List<String> cmd = new ArrayList<>();
                cmd.add(chromePath);

                if (currentMode == Mode.KIDS) {
                    // Kid: app mode (no tabs/address bar)
                    cmd.add("--app=" + app.url);
                    cmd.add("--start-fullscreen");
                    cmd.add("--user-data-dir=" + userDataDir);
                    cmd.add("--no-first-run");
                    cmd.add("--no-default-browser-check");
                    cmd.add("--disable-extensions");
                    cmd.add("--disable-popup-blocking");
                    // extra kids flags could go here
                } else {
                    // STUDENT mode: regular browser window with tabs allowed, but new profile dir
                    cmd.add("--new-window");
                    cmd.add(app.url); // URL arg must be separate so Chrome opens that page
                    cmd.add("--start-maximized");
                    cmd.add("--user-data-dir=" + userDataDir);
                    cmd.add("--no-first-run");
                    cmd.add("--no-default-browser-check");
                    cmd.add("--disable-extensions");
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                setActiveBrowserProcess(proc);

                final Process procRef = proc;
                new Thread(() -> {
                    try {
                        procRef.waitFor();
                    } catch (InterruptedException ignored) {}
                    if (windows) showTaskbarWindows();
                    clearActiveBrowserProcess();
                }, "BrowserMonitor-" + safeName).start();

                return;
            } else {
                // Chrome not found - fallback to default browser
                Desktop.getDesktop().browse(new URI(app.url));
                // best-effort restore after a short delay
                if (windows) {
                    new Thread(() -> {
                        try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                        showTaskbarWindows();
                    }, "TaskbarRestoreFallback").start();
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to open " + app.name + ": " + ex.getMessage());
            if (windows) showTaskbarWindows();
            try {
                if (app.url != null && app.url.startsWith("http")) Desktop.getDesktop().browse(new URI(app.url));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Set active browser process and show the exit button on the desktop.
     */
    private synchronized void setActiveBrowserProcess(Process p) {
        activeBrowserProcess = p;
        SwingUtilities.invokeLater(() -> exitBrowserBtn.setVisible(true));
    }

    /**
     * Clear active browser process reference and hide exit button.
     */
    private synchronized void clearActiveBrowserProcess() {
        activeBrowserProcess = null;
        SwingUtilities.invokeLater(() -> exitBrowserBtn.setVisible(false));
    }

    /**
     * Kill the active browser process launched by this app (if any)
     */
    private synchronized void closeActiveBrowserProcess() {
        if (activeBrowserProcess != null) {
            try {
                activeBrowserProcess.destroy();
                if (activeBrowserProcess.isAlive()) activeBrowserProcess.destroyForcibly();
            } catch (Exception ex) {
                System.err.println("Error closing browser process: " + ex.getMessage());
            } finally {
                activeBrowserProcess = null;
            }
        }
    }

    // ---------- PowerShell taskbar hide/show ----------
    private void hideTaskbarWindows() {
        try {
            String ps = ""
                    + "$sig = @'\n"
                    + "using System;\n"
                    + "using System.Runtime.InteropServices;\n"
                    + "public class T {\n"
                    + "  [DllImport(\"user32.dll\")] public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);\n"
                    + "  [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);\n"
                    + "}\n"
                    + "'@;\n"
                    + "Add-Type $sig -PassThru | Out-Null;\n"
                    + "$hwnd = [T]::FindWindow('Shell_TrayWnd',$null);\n"
                    + "if ($hwnd -ne [IntPtr]::Zero) { [T]::ShowWindow($hwnd,0) }";

            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", ps);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // consume streams
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.ready()) r.readLine();
            } catch (Exception ignored) {}
            p.waitFor();
        } catch (Exception ex) {
            System.err.println("hideTaskbarWindows error: " + ex.getMessage());
        }
    }

    private void showTaskbarWindows() {
        try {
            String ps = ""
                    + "$sig = @'\n"
                    + "using System;\n"
                    + "using System.Runtime.InteropServices;\n"
                    + "public class T {\n"
                    + "  [DllImport(\"user32.dll\")] public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);\n"
                    + "  [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);\n"
                    + "}\n"
                    + "'@;\n"
                    + "Add-Type $sig -PassThru | Out-Null;\n"
                    + "$hwnd = [T]::FindWindow('Shell_TrayWnd',$null);\n"
                    + "if ($hwnd -ne [IntPtr]::Zero) { [T]::ShowWindow($hwnd,5) }";

            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", ps);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.ready()) r.readLine();
            } catch (Exception ignored) {}
            p.waitFor();
        } catch (Exception ex) {
            System.err.println("showTaskbarWindows error: " + ex.getMessage());
        }
    }

    // ---------- start a repeating cycle timer for an app ----------
    private void startCycleTimerForApp(App app) {
        // Stop any previous timer first
        app.stopCycleTimer();

        if (!app.autoResetExtend || app.autoResetMinutes <= 0) return;

        // Ensure the app starts unlocked for the work phase.
        app.isBlocked = false;
        app.usedSeconds = 0;
        // cycleDelay in ms
        int delay = app.autoResetMinutes * 60 * 1000;

        // Timer toggles the blocked state each `delay` milliseconds
        app.cycleTimer = new javax.swing.Timer(delay, ev -> {
            app.isBlocked = !app.isBlocked;
            if (app.isBlocked) {
                // When it becomes blocked, close app processes if running
                try {
                    String u = app.url.toLowerCase();
                    if (u.contains("chrome") || u.contains("http")) {
                        Runtime.getRuntime().exec("cmd /c taskkill /IM chrome.exe /F");
                        Runtime.getRuntime().exec("cmd /c taskkill /IM msedge.exe /F");
                        Runtime.getRuntime().exec("cmd /c taskkill /IM firefox.exe /F");
                    }
                } catch (Exception ignored) {}
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        app.name + " is now LOCKED for " + app.autoResetMinutes + " minutes."));
            } else {
                // Unblocked: reset usage so child gets fresh work session
                app.usedSeconds = 0;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        app.name + " is now UNLOCKED for " + app.autoResetMinutes + " minutes."));
            }
        });
        app.cycleTimer.setRepeats(true);
        app.cycleTimer.start();
    }

    // ---------- open local or browser app ----------
    private void openApp(App app) {
        if (app.isBlocked) {
            JOptionPane.showMessageDialog(this, app.name + " is blocked. Parent must reset it.");
            return;
        }

        try {
            Process process = null;
            if ("word".equalsIgnoreCase(app.url)) process = Runtime.getRuntime().exec("cmd /c start winword");
            else if ("excel".equalsIgnoreCase(app.url)) process = Runtime.getRuntime().exec("cmd /c start excel");
            else if ("powerpnt".equalsIgnoreCase(app.url)) process = Runtime.getRuntime().exec("cmd /c start powerpnt");
            else if ("notepad".equalsIgnoreCase(app.url)) process = Runtime.getRuntime().exec("cmd /c start notepad");
            else if (app.url.endsWith(".exe") || app.url.endsWith(".lnk")) {
                // launch the executable directly
                process = Runtime.getRuntime().exec("cmd /c start \"\" \"" + app.url + "\"");
            } else if (app.url.startsWith("http")) {
                // browser behavior depends on mode
                launchBrowserAppWithRestrictions(app);
            } else {
                process = Runtime.getRuntime().exec("cmd /c start " + app.url);
            }

            // For local apps, keep previous minimize/unminimize behavior
            if (process != null && !(app.url.startsWith("http"))) {
                allowProgrammaticMinimize = true;
                setState(Frame.ICONIFIED);
                Thread.sleep(300);
                setState(Frame.NORMAL);
                allowProgrammaticMinimize = false;
            }

            app.sessionStartTime = System.currentTimeMillis();
            recentStartTime.put(app.name, app.sessionStartTime);
            app.usedSeconds = 0;

            if (app.timer != null) app.timer.stop();
            app.timer = new javax.swing.Timer(1000, ev -> {
                app.usedSeconds++;
                if (app.allowedMinutes != -1 && app.usedSeconds >= app.allowedMinutes * 60) {
                    closeAppAutomatically(app);
                }
            });
            app.timer.start();

            recentUsage.put(app.name, 0L);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to open " + app.name + ": " + ex.getMessage());
            if (System.getProperty("os.name").toLowerCase().contains("win")) showTaskbarWindows();
        }
    }

    // ---------- parent control UI ----------
    private void openParentControls() {
        if (!checkPassword("Enter Parent Password")) {
            JOptionPane.showMessageDialog(this, "Wrong Password!");
            return;
        }

        String[] appNames = APPS.stream().map(a -> a.name).toArray(String[]::new);
        String sel = (String) JOptionPane.showInputDialog(this, "Select App to Set Time or Reset:",
                "Parent Controls", JOptionPane.PLAIN_MESSAGE, null, appNames, appNames[0]);
        if (sel == null) return;

        App app = APPS.stream().filter(a -> a.name.equals(sel)).findFirst().orElse(null);
        if (app == null) return;

        String[] options = {"Set Time Limit", "Reset App", "Block/Unblock", "Enable/Disable Auto-Unblock", "Enable/Disable Auto-Reset & Extend Time"};
        String choice = (String) JOptionPane.showInputDialog(this,
                "Choose an action for " + app.name + ":",
                "Parent Controls", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

        if (choice == null) return;

        switch (choice) {
            case "Set Time Limit":
                String minutes = JOptionPane.showInputDialog(this, "Enter allowed time in minutes:");
                if (minutes != null && minutes.matches("\\d+")) {
                    app.allowedMinutes = Integer.parseInt(minutes);
                    JOptionPane.showMessageDialog(this, "Time limit set for " + app.name);
                }
                break;
            case "Reset App":
                app.usedSeconds = 0;
                app.isBlocked = false;
                JOptionPane.showMessageDialog(this, app.name + " has been reset.");
                break;
            case "Block/Unblock":
                app.isBlocked = !app.isBlocked;
                JOptionPane.showMessageDialog(this, app.name + (app.isBlocked ? " blocked." : " unblocked."));
                break;
            case "Enable/Disable Auto-Unblock":
                app.autoUnblock = !app.autoUnblock;
                if (app.autoUnblock) app.autoResetExtend = false;
                // Stop cycleTimer if present (avoid conflicts)
                if (!app.autoResetExtend) app.stopCycleTimer();
                JOptionPane.showMessageDialog(this,
                        "Auto-Unblock is now " + (app.autoUnblock ? "ENABLED" : "DISABLED")
                                + " for " + app.name);
                break;
            case "Enable/Disable Auto-Reset & Extend Time":
                // This is the cycle feature: X minutes work, X minutes lock, repeat
                app.autoResetExtend = !app.autoResetExtend;
                if (app.autoResetExtend) {
                    app.autoUnblock = false;
                    String mins = JOptionPane.showInputDialog(this, "Enter cycle time in minutes (work time and lock time):");
                    if (mins != null && mins.matches("\\d+")) {
                        app.autoResetMinutes = Integer.parseInt(mins);
                        // Start the repeating cycle timer
                        startCycleTimerForApp(app);
                        JOptionPane.showMessageDialog(this, "Auto-Reset & Extend Time ENABLED for " + app.name + " with cycle " + app.autoResetMinutes + " minutes.");
                    } else {
                        app.autoResetExtend = false;
                        JOptionPane.showMessageDialog(this, "Invalid minutes. Auto-Reset cancelled.");
                    }
                } else {
                    // disable cycle and stop timer
                    app.stopCycleTimer();
                    JOptionPane.showMessageDialog(this,
                            "Auto-Reset & Extend Time is now DISABLED for " + app.name);
                }
                break;
        }
    }

    private void openRecycleBin() {
        if (!checkPassword("Enter Parent Password to Access Recycle Bin")) {
            JOptionPane.showMessageDialog(this, "Wrong Password!");
            return;
        }

        if (RECYCLE_BIN.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Recycle Bin is empty.");
            return;
        }

        String[] deletedApps = RECYCLE_BIN.stream().map(a -> a.name).toArray(String[]::new);
        String choice = (String) JOptionPane.showInputDialog(this,
                "Select an app:", "Recycle Bin",
                JOptionPane.PLAIN_MESSAGE, null, deletedApps, deletedApps[0]);
        if (choice == null) return;

        App app = RECYCLE_BIN.stream().filter(a -> a.name.equals(choice)).findFirst().orElse(null);
        if (app == null) return;

        String[] options = {"Restore", "Permanently Delete"};
        String action = (String) JOptionPane.showInputDialog(this,
                "Choose action for " + app.name + ":",
                "Recycle Bin", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

        if (action == null) return;

        switch (action) {
            case "Restore":
                RECYCLE_BIN.remove(app);
                APPS.add(app);
                refreshIcons();
                break;
            case "Permanently Delete":
                // stop any timers before delete
                app.stopCycleTimer();
                if (app.timer != null) { app.timer.stop(); app.timer = null; }
                RECYCLE_BIN.remove(app);
                refreshIcons();
                break;
        }
    }

    // ---------- main ----------
    public static void main(String[] args) {
        // Prompt password & mode BEFORE creating the desktop frame (this avoids any flicker)
        Mode chosen = promptPasswordAndChooseMode();
        if (chosen == null) {
            // Either cancelled or wrong password
            System.exit(0);
        }

        // Create and show the desktop with the chosen mode (no initial flash)
        SwingUtilities.invokeLater(() -> {
            DraggableResizableDesktop instance = new DraggableResizableDesktop(chosen);
            instance.refreshIcons();
            instance.setVisible(true);
        });
    }
}
