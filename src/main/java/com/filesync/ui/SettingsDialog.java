package com.filesync.ui;

import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Modal serial-port settings dialog. */
public class SettingsDialog {
    public void showDialog(
            JFrame owner,
            SettingsManager settings,
            SerialPortManager serialPort,
            Runnable onSettingsUpdated,
            LogController logController) {
        JDialog dialog = new JDialog(owner, "COM Port Settings", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(350, 250);
        dialog.setLocationRelativeTo(owner);

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new java.awt.GridBagLayout());
        formPanel.setBorder(new javax.swing.border.EmptyBorder(15, 15, 15, 15));
        java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = java.awt.GridBagConstraints.WEST;

        JComboBox<Integer> baudRateCombo =
                addComboRow(formPanel, gbc, 0, "Baud Rate:", boxed(SettingsManager.BAUD_RATES));
        baudRateCombo.setSelectedItem(settings.getBaudRate());

        JComboBox<Integer> dataBitsCombo =
                addComboRow(
                        formPanel, gbc, 1, "Data Bits:", boxed(SettingsManager.DATA_BITS_OPTIONS));
        dataBitsCombo.setSelectedItem(settings.getDataBits());

        JComboBox<String> stopBitsCombo =
                addComboRow(formPanel, gbc, 2, "Stop Bits:", SettingsManager.STOP_BITS_NAMES);
        stopBitsCombo.setSelectedIndex(SettingsManager.getStopBitsIndex(settings.getStopBits()));

        JComboBox<String> parityCombo =
                addComboRow(formPanel, gbc, 3, "Parity:", SettingsManager.PARITY_NAMES);
        parityCombo.setSelectedIndex(SettingsManager.getParityIndex(settings.getParity()));

        JCheckBox debugCheckBox = new JCheckBox("Debug Mode");
        debugCheckBox.setSelected(settings.isDebugMode());
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        gbc.fill = java.awt.GridBagConstraints.NONE;
        gbc.weightx = 0;
        formPanel.add(debugCheckBox, gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        okButton.addActionListener(
                event -> {
                    // Save settings
                    settings.setBaudRate((Integer) baudRateCombo.getSelectedItem());
                    settings.setDataBits((Integer) dataBitsCombo.getSelectedItem());
                    settings.setStopBits(
                            SettingsManager.STOP_BITS_VALUES[stopBitsCombo.getSelectedIndex()]);
                    settings.setParity(
                            SettingsManager.PARITY_VALUES[parityCombo.getSelectedIndex()]);
                    settings.setDebugMode(debugCheckBox.isSelected());
                    settings.save();

                    if (logController != null) {
                        logController.setDebugMode(settings.isDebugMode());
                    }

                    serialPort.setBaudRate(settings.getBaudRate());
                    serialPort.setDataBits(settings.getDataBits());
                    serialPort.setStopBits(settings.getStopBits());
                    serialPort.setParity(settings.getParity());

                    if (onSettingsUpdated != null) {
                        onSettingsUpdated.run();
                    }
                    if (logController != null) {
                        logController.log(
                                "Settings updated: "
                                        + getSettingsString(settings)
                                        + ", Debug: "
                                        + (settings.isDebugMode() ? "ON" : "OFF"));
                    }
                    dialog.dispose();
                });

        cancelButton.addActionListener(event -> dialog.dispose());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        dialog.add(formPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    /**
     * Adds one labeled combo row to the form grid and returns the combo so the caller can wire its
     * initial selection. {@code gbc} is advanced to the row's two cells (label west, combo filled).
     */
    private static <T> JComboBox<T> addComboRow(
            JPanel formPanel,
            java.awt.GridBagConstraints gbc,
            int rowIndex,
            String labelText,
            T[] items) {
        gbc.gridx = 0;
        gbc.gridy = rowIndex;
        gbc.fill = java.awt.GridBagConstraints.NONE;
        gbc.weightx = 0;
        formPanel.add(new JLabel(labelText), gbc);

        JComboBox<T> combo = new JComboBox<>(items);
        gbc.gridx = 1;
        gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(combo, gbc);
        return combo;
    }

    /** Boxes an int option array for a {@code JComboBox<Integer>}. */
    private static Integer[] boxed(int[] values) {
        return java.util.Arrays.stream(values).boxed().toArray(Integer[]::new);
    }

    public static String getSettingsString(SettingsManager settings) {
        int stopBitsIdx = SettingsManager.getStopBitsIndex(settings.getStopBits());
        int parityIdx = SettingsManager.getParityIndex(settings.getParity());
        return String.format(
                "%d baud, %d data bits, %s stop bits, %s parity",
                settings.getBaudRate(),
                settings.getDataBits(),
                SettingsManager.STOP_BITS_NAMES[stopBitsIdx],
                SettingsManager.PARITY_NAMES[parityIdx]);
    }
}
