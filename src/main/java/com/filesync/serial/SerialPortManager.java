package com.filesync.serial;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages serial port communication using jSerialComm library.
 * Provides methods for listing, opening, closing ports and reading/writing data.
 */
public class SerialPortManager {

    private static final int DEFAULT_BAUD_RATE = 115200;
    private static final int DEFAULT_DATA_BITS = 8;
    private static final int DEFAULT_STOP_BITS = SerialPort.ONE_STOP_BIT;
    private static final int DEFAULT_PARITY = SerialPort.NO_PARITY;
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private SerialPort serialPort;
    private InputStream inputStream;
    private OutputStream outputStream;
    private int baudRate;

    public SerialPortManager() {
        this.baudRate = DEFAULT_BAUD_RATE;
    }

    public SerialPortManager(int baudRate) {
        this.baudRate = baudRate;
    }

    /**
     * Get list of available COM ports
     */
    public static List<String> getAvailablePorts() {
        List<String> portNames = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            portNames.add(port.getSystemPortName());
        }
        return portNames;
    }

    /**
     * Get list of available COM ports with descriptions
     */
    public static List<String> getAvailablePortsWithDescription() {
        List<String> portDescs = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            portDescs.add(port.getSystemPortName() + " - " + port.getDescriptivePortName());
        }
        return portDescs;
    }

    /**
     * Open the specified COM port
     */
    public boolean open(String portName) {
        try {
            serialPort = SerialPort.getCommPort(portName);
            serialPort.setBaudRate(baudRate);
            serialPort.setNumDataBits(DEFAULT_DATA_BITS);
            serialPort.setNumStopBits(DEFAULT_STOP_BITS);
            serialPort.setParity(DEFAULT_PARITY);
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, DEFAULT_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);

            if (serialPort.openPort()) {
                inputStream = serialPort.getInputStream();
                outputStream = serialPort.getOutputStream();
                return true;
            }
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Close the serial port
     */
    public void close() {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if (serialPort != null && serialPort.isOpen()) {
                serialPort.closePort();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        inputStream = null;
        outputStream = null;
        serialPort = null;
    }

    /**
     * Check if port is open
     */
    public boolean isOpen() {
        return serialPort != null && serialPort.isOpen();
    }

    /**
     * Write data to the serial port
     */
    public void write(byte[] data) throws IOException {
        if (!isOpen()) {
            throw new IOException("Serial port is not open");
        }
        outputStream.write(data);
        outputStream.flush();
    }

    /**
     * Write a single byte to the serial port
     */
    public void write(int b) throws IOException {
        if (!isOpen()) {
            throw new IOException("Serial port is not open");
        }
        outputStream.write(b);
        outputStream.flush();
    }

    /**
     * Read data from the serial port
     */
    public int read(byte[] buffer) throws IOException {
        if (!isOpen()) {
            throw new IOException("Serial port is not open");
        }
        return inputStream.read(buffer);
    }

    /**
     * Read a single byte from the serial port with timeout
     */
    public int read() throws IOException {
        if (!isOpen()) {
            throw new IOException("Serial port is not open");
        }
        return inputStream.read();
    }

    /**
     * Read exact number of bytes with timeout
     */
    public byte[] readExact(int length, int timeoutMs) throws IOException {
        if (!isOpen()) {
            throw new IOException("Serial port is not open");
        }

        byte[] buffer = new byte[length];
        int bytesRead = 0;
        long startTime = System.currentTimeMillis();

        while (bytesRead < length) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new IOException("Read timeout: expected " + length + " bytes, got " + bytesRead);
            }

            int available = inputStream.available();
            if (available > 0) {
                int toRead = Math.min(available, length - bytesRead);
                int read = inputStream.read(buffer, bytesRead, toRead);
                if (read > 0) {
                    bytesRead += read;
                }
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Read interrupted");
                }
            }
        }
        return buffer;
    }

    /**
     * Read a line (until newline character)
     */
    public String readLine(int timeoutMs) throws IOException {
        if (!isOpen()) {
            throw new IOException("Serial port is not open");
        }

        StringBuilder sb = new StringBuilder();
        long startTime = System.currentTimeMillis();

        while (true) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                if (sb.length() > 0) {
                    return sb.toString();
                }
                throw new IOException("Read timeout");
            }

            int b = inputStream.read();
            if (b == -1) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Read interrupted");
                }
                continue;
            }

            if (b == '\n') {
                return sb.toString();
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }
    }

    /**
     * Write a line (append newline)
     */
    public void writeLine(String line) throws IOException {
        write((line + "\n").getBytes());
    }

    /**
     * Get the number of bytes available to read
     */
    public int available() throws IOException {
        if (!isOpen()) {
            return 0;
        }
        return inputStream.available();
    }

    /**
     * Clear the input buffer
     */
    public void clearInputBuffer() throws IOException {
        if (!isOpen()) {
            return;
        }
        while (inputStream.available() > 0) {
            inputStream.read();
        }
    }

    /**
     * Set read timeout
     */
    public void setReadTimeout(int timeoutMs) {
        if (serialPort != null) {
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, timeoutMs, timeoutMs);
        }
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
        if (serialPort != null) {
            serialPort.setBaudRate(baudRate);
        }
    }

    public String getPortName() {
        return serialPort != null ? serialPort.getSystemPortName() : null;
    }
}

