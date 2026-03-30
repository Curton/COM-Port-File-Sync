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

        gbc.gridx = 0;
        gbc.gridy = 0;
        formPanel.add(new JLabel("Baud Rate:"), gbc);

        JComboBox<Integer> baudRateCombo = new JComboBox<>();
        for (int rate : SettingsManager.BAUD_RATES) {
            baudRateCombo.addItem(rate);
        }
        baudRateCombo.setSelectedItem(settings.getBaudRate());
        gbc.gridx = 1;
        gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(baudRateCombo, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = java.awt.GridBagConstraints.NONE;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Data Bits:"), gbc);

        JComboBox<Integer> dataBitsCombo = new JComboBox<>();
        for (int bits : SettingsManager.DATA_BITS_OPTIONS) {
            dataBitsCombo.addItem(bits);
        }
        dataBitsCombo.setSelectedItem(settings.getDataBits());
        gbc.gridx = 1;
        gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(dataBitsCombo, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = java.awt.GridBagConstraints.NONE;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Stop Bits:"), gbc);

        JComboBox<String> stopBitsCombo = new JComboBox<>(SettingsManager.STOP_BITS_NAMES);
        stopBitsCombo.setSelectedIndex(SettingsManager.getStopBitsIndex(settings.getStopBits()));
        gbc.gridx = 1;
        gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(stopBitsCombo, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.fill = java.awt.GridBagConstraints.NONE;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Parity:"), gbc);

        JComboBox<String> parityCombo = new JComboBox<>(SettingsManager.PARITY_NAMES);
        parityCombo.setSelectedIndex(SettingsManager.getParityIndex(settings.getParity()));
        gbc.gridx = 1;
        gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(parityCombo, gbc);

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
