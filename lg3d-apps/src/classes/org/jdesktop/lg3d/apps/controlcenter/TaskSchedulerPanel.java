/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import org.jdesktop.lg3d.utils.taskscheduler.CronExpression;
import org.jdesktop.lg3d.utils.taskscheduler.MisfirePolicy;
import org.jdesktop.lg3d.utils.taskscheduler.ScheduledTask;
import org.jdesktop.lg3d.utils.taskscheduler.TaskExecutionRecord;
import org.jdesktop.lg3d.utils.taskscheduler.TaskScheduler;
import org.jdesktop.lg3d.utils.taskscheduler.TaskValidator;
import org.jdesktop.lg3d.utils.taskscheduler.Trigger;

/**
 * Task Scheduler panel: the desktop's {@code crontab}. It lets the user create,
 * edit, enable/disable, run-on-demand and delete scheduled tasks that launch a
 * program (an argv command run directly, never through a shell) or a start-menu
 * application, on a cron expression, a fixed interval, or a one-shot instant.
 * Each task carries its own timeout, misfire policy, concurrency guard, retries,
 * working directory and environment overlay; the panel also shows the recent run
 * history so the schedule is auditable.
 *
 * <p>This is a completely separate feature from the wallpaper/lighting
 * {@link SchedulePanel} - it does not read or write that schedule.</p>
 *
 * <p>All choice controls are {@code JList} selectors (never combo boxes) because
 * the Control Center is rendered offscreen inside a SwingNode where popup editors
 * misbehave. Construction is headless-safe (no window is realised) and the panel
 * only <em>reads</em> state until the user presses Save, so opening it never
 * writes preferences.</p>
 */
public class TaskSchedulerPanel implements ControlPanel {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] KINDS = { "Cron expression", "Fixed interval", "One time" };
    private static final String[] ACTIONS = { "Run a command", "Launch a start-menu app" };
    private static final String[] ON_OFF = { "Off", "On" };
    private static final String[] UNITS = { "seconds", "minutes", "hours" };
    private static final long[] UNIT_MILLIS = { 1000L, 60_000L, 3_600_000L };
    private static final String[] MISFIRE_LABELS = {
        "Run once as soon as possible", "Skip and wait for next time", "Run on the next tick"
    };
    private static final MisfirePolicy[] MISFIRE_VALUES = {
        MisfirePolicy.FIRE_ONCE_NOW, MisfirePolicy.IGNORE, MisfirePolicy.FIRE_ON_NEXT_TICK
    };
    /** Common crontab presets; selecting one fills the cron field. */
    private static final String[] PRESET_LABELS = {
        "Every minute", "Every 5 minutes", "Hourly", "Daily at midnight",
        "Weekly (Sun 00:00)", "Monthly (1st 00:00)", "Yearly", "On reboot"
    };
    private static final String[] PRESET_EXPRS = {
        "* * * * *", "*/5 * * * *", "0 * * * *", "0 0 * * *",
        "0 0 * * 0", "0 0 1 * *", "0 0 1 1 *", "@reboot"
    };

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel statusLabel = new JLabel(" ");

    // --- Task list -------------------------------------------------------
    private final DefaultListModel<String> taskNames = new DefaultListModel<>();
    private final JList<String> taskList = new JList<>(taskNames);

    // --- Editor fields ---------------------------------------------------
    private final JTextField nameField = new JTextField(24);
    private final JList<String> enabledList = list(ON_OFF, 2);
    private final JList<String> actionList = list(ACTIONS, 2);
    private final JList<String> kindList = list(KINDS, 3);

    private final JPanel triggerCards = new JPanel(new CardLayout());
    private final JTextField cronField = new JTextField(18);
    private final JLabel cronPreview = new JLabel(" ");
    private final JList<String> presetList = list(PRESET_LABELS, 4);
    private final JSpinner intervalValue =
            new JSpinner(new SpinnerNumberModel(5, 1, 1_000_000, 1));
    private final JList<String> unitList = list(UNITS, 3);
    private final JSpinner onceYear = new JSpinner(new SpinnerNumberModel(2026, 1970, 2999, 1));
    private final JSpinner onceMonth = new JSpinner(new SpinnerNumberModel(1, 1, 12, 1));
    private final JSpinner onceDay = new JSpinner(new SpinnerNumberModel(1, 1, 31, 1));
    private final JSpinner onceHour = new JSpinner(new SpinnerNumberModel(0, 0, 23, 1));
    private final JSpinner onceMinute = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));

    private final JTextField commandField = new JTextField(30);
    private final JTextField workdirField = new JTextField(24);
    private final JSpinner timeoutSpinner =
            new JSpinner(new SpinnerNumberModel(300, 0, 86_400, 30));
    private final JList<String> skipList = list(ON_OFF, 2);
    private final JList<String> misfireList = list(MISFIRE_LABELS, 3);
    private final JSpinner retrySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10, 1));
    private final JSpinner retryDelaySpinner =
            new JSpinner(new SpinnerNumberModel(0, 0, 86_400, 5));

    private final DefaultListModel<String> envNames = new DefaultListModel<>();
    private final JList<String> envList = new JList<>(envNames);
    private final JTextField envKeyField = new JTextField(12);
    private final JTextField envValueField = new JTextField(16);

    // --- History ---------------------------------------------------------
    private final DefaultListModel<String> historyNames = new DefaultListModel<>();
    private final JList<String> historyList = new JList<>(historyNames);

    /** The task being edited; a detached copy until Save commits it. */
    private ScheduledTask draft;
    /** The model snapshot behind {@link #taskList}, parallel to its indices. */
    private final List<ScheduledTask> model = new ArrayList<>();

    public TaskSchedulerPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(buildHelp(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);
        wireListeners();
        newTask();
        reload();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JComponent buildHelp() {
        JLabel help = new JLabel(
                "<html><i>Schedule programs and start-menu apps like <b>crontab</b>. A command is run "
                + "directly as an argument vector &mdash; never through a shell &mdash; so it cannot be "
                + "turned into a shell injection. Use <b>Run now</b> to test a task immediately.</i></html>");
        JPanel p = new JPanel(new BorderLayout());
        p.add(help, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildCenter() {
        JPanel content = new JPanel(new BorderLayout(8, 8));

        // Left: the task list + New/Delete.
        taskList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane taskScroll = new JScrollPane(taskList);
        taskScroll.setPreferredSize(new Dimension(230, 360));
        taskScroll.setBorder(BorderFactory.createTitledBorder("Scheduled tasks"));
        JButton newButton = new JButton("New");
        newButton.addActionListener(e -> newTask());
        JButton deleteButton = new JButton("Delete");
        deleteButton.addActionListener(e -> deleteSelected());
        JPanel taskButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        taskButtons.add(newButton);
        taskButtons.add(deleteButton);
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(taskScroll, BorderLayout.CENTER);
        left.add(taskButtons, BorderLayout.SOUTH);

        content.add(left, BorderLayout.WEST);
        content.add(new JScrollPane(buildEditor()), BorderLayout.CENTER);
        return content;
    }

    private JComponent buildEditor() {
        // A vertical BoxLayout (not GridLayout) so every row keeps its own
        // preferred height and packs from the top; GridLayout would stretch all
        // rows to equal tall bands and clip the trigger card at the bottom.
        JPanel ed = new JPanel();
        ed.setLayout(new BoxLayout(ed, BoxLayout.Y_AXIS));
        ed.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        triggerCards.add(buildCronCard(), "CRON");
        triggerCards.add(buildIntervalCard(), "INTERVAL");
        triggerCards.add(buildOnceCard(), "ONCE");

        List<JComponent> rows = new ArrayList<>();
        rows.add(row("Name:", nameField));
        rows.add(row("Enabled:", scroll(enabledList, 72, 58), "Action:", scroll(actionList, 200, 58)));
        rows.add(row("Schedule:", scroll(kindList, 200, 74)));
        rows.add(triggerCards);
        rows.add(row("Command:", commandField));
        rows.add(row("Working dir:", workdirField));
        rows.add(row("Timeout (s):", timeoutSpinner, "Skip if running:", scroll(skipList, 72, 58)));
        rows.add(row("If missed:", scroll(misfireList, 220, 74)));
        rows.add(row("Retries:", retrySpinner, "Retry delay (s):", retryDelaySpinner));
        rows.add(buildEnvPanel());

        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                ed.add(Box.createVerticalStrut(6));
            }
            JComponent r = rows.get(i);
            ed.add(r);
            // Cap the height at the row's preferred size (so BoxLayout never
            // stretches it) but let the width grow to fill the editor column.
            Dimension pref = r.getPreferredSize();
            r.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
            r.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        }
        return ed;
    }

    private JComponent buildCronCard() {
        JPanel p = new JPanel(new BorderLayout(6, 4));
        JPanel fieldRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fieldRow.add(new JLabel("Cron:"));
        fieldRow.add(cronField);
        JButton check = new JButton("Check");
        check.addActionListener(e -> previewCron());
        fieldRow.add(check);
        JScrollPane presetScroll = scroll(presetList, 180, 76);
        presetScroll.setBorder(BorderFactory.createTitledBorder("Presets"));
        p.add(fieldRow, BorderLayout.NORTH);
        p.add(presetScroll, BorderLayout.CENTER);
        p.add(cronPreview, BorderLayout.SOUTH);
        p.setBorder(BorderFactory.createTitledBorder(
                "Cron: min hour dom month dow  (or @daily, @reboot)"));
        return p;
    }

    private JComponent buildIntervalCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.add(new JLabel("Every:"));
        p.add(intervalValue);
        p.add(scroll(unitList, 100, 74));
        p.setBorder(BorderFactory.createTitledBorder("Fixed interval"));
        return p;
    }

    private JComponent buildOnceCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.add(new JLabel("At:"));
        p.add(onceYear);
        p.add(new JLabel("-"));
        p.add(onceMonth);
        p.add(new JLabel("-"));
        p.add(onceDay);
        p.add(new JLabel("  "));
        p.add(onceHour);
        p.add(new JLabel(":"));
        p.add(onceMinute);
        p.setBorder(BorderFactory.createTitledBorder("One time (yyyy-mm-dd hh:mm, local)"));
        return p;
    }

    private JComponent buildEnvPanel() {
        envList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane envScroll = scroll(envList, 240, 76);
        envScroll.setBorder(BorderFactory.createTitledBorder("Environment overlay (optional)"));
        JButton addEnv = new JButton("Add");
        addEnv.addActionListener(e -> addEnv());
        JButton removeEnv = new JButton("Remove");
        removeEnv.addActionListener(e -> removeEnv());
        JPanel editRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        editRow.add(new JLabel("Name:"));
        editRow.add(envKeyField);
        editRow.add(new JLabel("Value:"));
        editRow.add(envValueField);
        editRow.add(addEnv);
        editRow.add(removeEnv);
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(envScroll, BorderLayout.CENTER);
        p.add(editRow, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildSouth() {
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane historyScroll = new JScrollPane(historyList);
        historyScroll.setPreferredSize(new Dimension(560, 96));
        historyScroll.setBorder(BorderFactory.createTitledBorder("Recent runs"));

        JButton save = new JButton("Save");
        save.addActionListener(e -> save());
        JButton runNow = new JButton("Run now");
        runNow.addActionListener(e -> runNow());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> reload());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(save);
        buttons.add(runNow);
        buttons.add(refresh);

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(historyScroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(buttons, BorderLayout.CENTER);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        south.add(bottom, BorderLayout.SOUTH);
        return south;
    }

    private void wireListeners() {
        taskList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int idx = taskList.getSelectedIndex();
            if (idx >= 0 && idx < model.size()) {
                loadDraft(model.get(idx).copy());
            }
        });
        kindList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showTriggerCard();
            }
        });
        presetList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int i = presetList.getSelectedIndex();
                if (i >= 0) {
                    cronField.setText(PRESET_EXPRS[i]);
                    previewCron();
                }
            }
        });
        enabledList.setSelectedIndex(1);
        skipList.setSelectedIndex(1);
        kindList.setSelectedIndex(0);
    }

    // ------------------------------------------------------------------
    // ControlPanel
    // ------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Task Scheduler";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        reload();
    }

    // ------------------------------------------------------------------
    // Model <-> UI
    // ------------------------------------------------------------------

    /** Re-reads the task list from the scheduler (read-only) and refreshes the list. */
    private void reload() {
        String keepId = draft == null ? null : draft.getId();
        model.clear();
        taskNames.clear();
        for (ScheduledTask t : TaskScheduler.get().tasks()) {
            model.add(t);
            taskNames.addElement(t.summary());
        }
        int select = -1;
        if (keepId != null) {
            for (int i = 0; i < model.size(); i++) {
                if (keepId.equals(model.get(i).getId())) {
                    select = i;
                    break;
                }
            }
        }
        if (select >= 0) {
            taskList.setSelectedIndex(select);
        } else if (!model.isEmpty()) {
            taskList.setSelectedIndex(0);
            loadDraft(model.get(0).copy());
        }
        refreshHistory();
    }

    private void newTask() {
        ScheduledTask t = new ScheduledTask();
        t.setName("New task");
        t.setTrigger(Trigger.cron("0 * * * *"));
        t.getArgv().add("/bin/echo");
        t.getArgv().add("hello");
        loadDraft(t);
        taskList.clearSelection();
        statusLabel.setText("Editing a new task - press Save to add it");
    }

    /** Loads {@code t} into the draft and every editor field. */
    private void loadDraft(ScheduledTask t) {
        draft = t;
        nameField.setText(t.getName() == null ? "" : t.getName());
        enabledList.setSelectedIndex(t.isEnabled() ? 1 : 0);
        actionList.setSelectedIndex(
                t.getAction() == ScheduledTask.Action.APP ? 1 : 0);

        Trigger tr = t.getTrigger();
        Trigger.Kind kind = tr == null ? Trigger.Kind.CRON : tr.getKind();
        kindList.setSelectedIndex(kind.ordinal());
        showTriggerCard();
        if (tr != null) {
            switch (kind) {
                case CRON:
                    cronField.setText(tr.getCron() == null ? "" : tr.getCron());
                    break;
                case INTERVAL:
                    loadInterval(tr.getPeriodMillis());
                    break;
                case ONCE:
                    loadOnce(tr.getAtEpochMillis());
                    break;
                default:
                    break;
            }
        }
        previewCron();

        commandField.setText(joinArgv(t.getArgv()));
        workdirField.setText(t.getWorkingDir());
        timeoutSpinner.setValue(Long.valueOf(t.getTimeoutSeconds()));
        skipList.setSelectedIndex(t.isSkipIfRunning() ? 1 : 0);
        misfireList.setSelectedIndex(misfireIndex(t.getMisfirePolicy()));
        retrySpinner.setValue(Integer.valueOf(t.getRetryCount()));
        retryDelaySpinner.setValue(Long.valueOf(t.getRetryDelayMillis() / 1000L));

        envNames.clear();
        for (Map.Entry<String, String> e : t.getEnv().entrySet()) {
            envNames.addElement(e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()));
        }
        refreshHistory();
    }

    private void loadInterval(long periodMillis) {
        int unit = 1;                       // default minutes
        if (periodMillis % 3_600_000L == 0) {
            unit = 2;
        } else if (periodMillis % 60_000L == 0) {
            unit = 1;
        } else {
            unit = 0;
        }
        unitList.setSelectedIndex(unit);
        long value = Math.max(1, periodMillis / UNIT_MILLIS[unit]);
        intervalValue.setValue(Long.valueOf(Math.min(value, 1_000_000L)));
    }

    private void loadOnce(long epochMillis) {
        ZonedDateTime z = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        onceYear.setValue(Integer.valueOf(z.getYear()));
        onceMonth.setValue(Integer.valueOf(z.getMonthValue()));
        onceDay.setValue(Integer.valueOf(z.getDayOfMonth()));
        onceHour.setValue(Integer.valueOf(z.getHour()));
        onceMinute.setValue(Integer.valueOf(z.getMinute()));
    }

    private void showTriggerCard() {
        int i = kindList.getSelectedIndex();
        String card = i == 1 ? "INTERVAL" : (i == 2 ? "ONCE" : "CRON");
        ((CardLayout) triggerCards.getLayout()).show(triggerCards, card);
    }

    // ------------------------------------------------------------------
    // Commit
    // ------------------------------------------------------------------

    /** Writes the editor fields into {@link #draft}; throws on malformed input. */
    private void commitDraft() {
        if (draft == null) {
            draft = new ScheduledTask();
        }
        draft.setName(nameField.getText());
        draft.setEnabled(enabledList.getSelectedIndex() == 1);
        draft.setAction(actionList.getSelectedIndex() == 1
                ? ScheduledTask.Action.APP : ScheduledTask.Action.COMMAND);

        int kind = kindList.getSelectedIndex();
        if (kind == 1) {
            int unit = Math.max(0, unitList.getSelectedIndex());
            long value = ((Number) intervalValue.getValue()).longValue();
            draft.setTrigger(Trigger.interval(Math.max(1, value * UNIT_MILLIS[unit])));
        } else if (kind == 2) {
            ZonedDateTime z = ZonedDateTime.of(
                    ((Number) onceYear.getValue()).intValue(),
                    ((Number) onceMonth.getValue()).intValue(),
                    ((Number) onceDay.getValue()).intValue(),
                    ((Number) onceHour.getValue()).intValue(),
                    ((Number) onceMinute.getValue()).intValue(),
                    0, 0, ZoneId.systemDefault());
            draft.setTrigger(Trigger.once(z.toInstant().toEpochMilli()));
        } else {
            draft.setTrigger(Trigger.cron(cronField.getText()));   // validates
        }

        draft.setArgv(tokenize(commandField.getText()));
        draft.setWorkingDir(workdirField.getText());
        draft.setTimeoutSeconds(((Number) timeoutSpinner.getValue()).longValue());
        draft.setSkipIfRunning(skipList.getSelectedIndex() == 1);
        draft.setMisfirePolicy(MISFIRE_VALUES[Math.max(0, misfireList.getSelectedIndex())]);
        draft.setRetryCount(((Number) retrySpinner.getValue()).intValue());
        draft.setRetryDelayMillis(((Number) retryDelaySpinner.getValue()).longValue() * 1000L);
    }

    private void save() {
        try {
            commitDraft();
        } catch (IllegalArgumentException ex) {
            statusLabel.setText("Not saved: " + ex.getMessage());
            return;
        }
        List<String> problems = TaskValidator.validate(draft);
        if (!problems.isEmpty()) {
            statusLabel.setText("Not saved: " + String.join("; ", problems));
            return;
        }
        try {
            TaskScheduler.get().save(draft);
            statusLabel.setText("Saved \"" + draft.getName() + "\"");
            reload();
        } catch (RuntimeException ex) {
            statusLabel.setText("Not saved: " + ex.getMessage());
        }
    }

    private void deleteSelected() {
        int idx = taskList.getSelectedIndex();
        if (idx < 0 || idx >= model.size()) {
            statusLabel.setText("Select a task to delete");
            return;
        }
        ScheduledTask t = model.get(idx);
        TaskScheduler.get().delete(t.getId());
        statusLabel.setText("Deleted \"" + t.getName() + "\"");
        newTask();
        reload();
    }

    private void runNow() {
        if (draft == null) {
            return;
        }
        // Persist first so an unsaved edit is what actually runs.
        try {
            commitDraft();
            if (TaskValidator.isValid(draft)) {
                TaskScheduler.get().save(draft);
                TaskScheduler.get().runNow(draft.getId());
                statusLabel.setText("Running \"" + draft.getName() + "\" now");
                reload();
            } else {
                statusLabel.setText("Fix the task before running it");
            }
        } catch (IllegalArgumentException ex) {
            statusLabel.setText("Cannot run: " + ex.getMessage());
        }
    }

    private void previewCron() {
        String expr = cronField.getText();
        if (expr == null || expr.trim().isEmpty()) {
            cronPreview.setText(" ");
            return;
        }
        try {
            CronExpression c = CronExpression.parse(expr);
            if (c.isReboot()) {
                cronPreview.setText("Runs once each time the desktop starts");
                return;
            }
            java.util.Optional<ZonedDateTime> next = c.nextFireTime(ZonedDateTime.now());
            cronPreview.setText(next.isPresent()
                    ? "Next run: " + WHEN.format(next.get())
                    : "This expression never matches");
        } catch (IllegalArgumentException ex) {
            cronPreview.setText("Invalid: " + ex.getMessage());
        }
    }

    private void refreshHistory() {
        historyNames.clear();
        if (draft == null) {
            return;
        }
        for (TaskExecutionRecord r : TaskScheduler.get().history(draft.getId())) {
            String when = WHEN.format(ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(r.getStartedAtMillis()),
                    ZoneId.systemDefault()));
            historyNames.addElement(when + "  " + r.getStatus()
                    + "  exit=" + r.getExitCode() + "  (" + r.getDurationMillis() + "ms)");
        }
    }

    private void addEnv() {
        String k = envKeyField.getText();
        if (k == null || k.trim().isEmpty()) {
            statusLabel.setText("Enter an environment variable name");
            return;
        }
        if (draft == null) {
            draft = new ScheduledTask();
        }
        draft.getEnv().put(k.trim(), envValueField.getText() == null ? "" : envValueField.getText());
        envNames.addElement(k.trim() + "=" + draft.getEnv().get(k.trim()));
        envKeyField.setText("");
        envValueField.setText("");
    }

    private void removeEnv() {
        int idx = envList.getSelectedIndex();
        if (idx < 0 || draft == null) {
            return;
        }
        String line = envNames.getElementAt(idx);
        int eq = line.indexOf('=');
        if (eq > 0) {
            draft.getEnv().remove(line.substring(0, eq));
        }
        envNames.remove(idx);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static int misfireIndex(MisfirePolicy p) {
        for (int i = 0; i < MISFIRE_VALUES.length; i++) {
            if (MISFIRE_VALUES[i] == p) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Splits a command line into an argv vector on whitespace, honouring double
     * quotes so an argument may contain spaces. This is a tokeniser, not a shell:
     * no expansion, redirection or metacharacter interpretation happens, and the
     * resulting vector is handed to {@code ProcessBuilder} verbatim.
     */
    static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean hasToken = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                hasToken = true;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (hasToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
            } else {
                cur.append(c);
                hasToken = true;
            }
        }
        if (hasToken) {
            out.add(cur.toString());
        }
        return out;
    }

    /** The inverse of {@link #tokenize}: quotes arguments that contain spaces. */
    static String joinArgv(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (a == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (a.indexOf(' ') >= 0) {
                sb.append('"').append(a).append('"');
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }

    private static JList<String> list(String[] items, int visibleRows) {
        DefaultListModel<String> m = new DefaultListModel<>();
        for (String s : items) {
            m.addElement(s);
        }
        JList<String> l = new JList<>(m);
        l.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        l.setVisibleRowCount(visibleRows);
        return l;
    }

    private static JScrollPane scroll(JList<String> l, int w, int h) {
        JScrollPane sp = new JScrollPane(l);
        sp.setPreferredSize(new Dimension(w, h));
        return sp;
    }

    /** Builds a left-to-right row of alternating labels and fields. */
    private static JPanel row(Object... cells) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Object c : cells) {
            p.add(c instanceof java.awt.Component
                    ? (java.awt.Component) c : new JLabel(String.valueOf(c)));
        }
        return p;
    }

    // --- Package-private test hooks ------------------------------------

    /** The number of tasks currently shown (test seam). */
    int taskCount() {
        return model.size();
    }

    /** The tokeniser exposed for tests. */
    List<String> tokenizeForTest(String line) {
        return tokenize(line);
    }
}
