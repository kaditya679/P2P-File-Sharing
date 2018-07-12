package com.tambapps.p2p.peer_transfer.desktop;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.tambapps.p2p.file_sharing.*;

import com.tambapps.p2p.peer_transfer.desktop.command.Arguments;
import com.tambapps.p2p.peer_transfer.desktop.command.ReceiveCommand;
import com.tambapps.p2p.peer_transfer.desktop.command.SendCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.io.IOException;

import java.net.SocketException;
import java.util.Objects;

@SpringBootApplication
public class FileSharingApplication {
	private final static String RECEIVE = "receive";
	private final static String SEND = "send";

	public static void main(String[] args) {
		Arguments arguments = new Arguments();
		ReceiveCommand receiveCommand = new ReceiveCommand();
		SendCommand sendCommand = new SendCommand();
		JCommander jCommander = JCommander.newBuilder()
				.addObject(arguments)
				.addCommand(RECEIVE, receiveCommand)
				.addCommand(SEND, sendCommand)
				.build();

		try {
			jCommander.parse(args);
		} catch (ParameterException e) {
			System.out.println("Error: " + e.getMessage());
			return;
		}

		if (arguments.getHelp()) {
			printHelp(jCommander);
			return;
		}

		String command = jCommander.getParsedCommand();
		if (command == null) {
			SpringApplication.run(FileSharingApplication.class, args);
			return;
		}
		switch (command) {
			case RECEIVE:
				try {
					receive(receiveCommand);
				} catch (IOException e) {
					System.out.println("Error while receiving file");
					e.printStackTrace();
				}
				break;
			case SEND:
				try {
					send(sendCommand);
				} catch (IOException e) {
					System.out.println("Error while sending file");
					e.printStackTrace();
				}
				break;
		}
	}

	private static void send(SendCommand sendCommand) throws IOException {
		String address = sendCommand.getIp();
		if (address == null) {
			try {
				address = Objects.requireNonNull(IPUtils.getIPAddress()).getHostAddress();
			} catch (SocketException|NullPointerException e) {
				System.err.println("Couldn't get ip address (are you connected to the internet?)");
			}
		}

		int port = sendCommand.getPort();
		FileSender fileSender;
		if (port == 0) {
			fileSender = new FileSender(address);
		} else {
			fileSender = new FileSender(address, port);
		}
		fileSender.setTransferListener(new TransferListener() {
			final String progressFormat = "Sent %s / %s";
			@Override
			public void onConnected(String remoteAddress, int remotePort, String fileName,
									long fileSize) {
				System.out.println("Connected to peer " + remoteAddress + ":" + remotePort);
				System.out.print(String.format(progressFormat, "0kb",
						TransferListener.bytesToString(fileSize)));
			}

			@Override
			public void onProgressUpdate(int progress, long byteProcessed, long totalBytes) {
				System.out.print("\r" + String.format(progressFormat,
						TransferListener.bytesToString(byteProcessed),
						TransferListener.bytesToString(totalBytes)));
			}
		});

		for (String filePath : sendCommand.getFilePath()) {
			File file = new File(filePath);
			System.out.println("Sending " + file.getName());
			System.out.println("Waiting for a connection on " + fileSender.getIp() + ":" + fileSender.getPort());
			fileSender.send(file);
			System.out.println();
			System.out.println(file.getName() + " was successfully sent");
			System.out.println();
		}
	}

	private static void receive(ReceiveCommand receiveCommand) throws IOException {
		FileReceiver fileReceiver = new FileReceiver(receiveCommand.getDownloadPath());

		fileReceiver.setTransferListener(new TransferListener() {
			final String progressFormat = "Received %s / %s";
			@Override
			public void onConnected(String remoteAddress, int remotePort, String fileName,
									long fileSize) {
				System.out.println("Connected to peer " + remoteAddress + ":" + remotePort);
				System.out.println("Receiving " + fileName);
				System.out.print(String.format(progressFormat, "0kb",
						TransferListener.bytesToString(fileSize)));
			}

			@Override
			public void onProgressUpdate(int progress, long byteProcessed, long totalBytes) {
				System.out.print("\r" + String.format(progressFormat,
						TransferListener.bytesToString(byteProcessed),
						TransferListener.bytesToString(totalBytes)));
			}
		});
		Peer peer = receiveCommand.getPeer();
		for (int i = 0; i < receiveCommand.getCount(); i++) {
			System.out.println("Connecting to " + peer);
			fileReceiver.receiveFrom(peer);
			System.out.println();
			System.out.println("Received " + fileReceiver.getReceivedFile().getName() + " successfully");
		}
	}

	private static void printHelp(JCommander jCommander) {
		System.out.println("To start the desktop app, run with the argument '-download.path=...'");
		System.out.println("Or you can use the command line tool:");
		jCommander.usage(SEND);

		jCommander.usage(RECEIVE);
	}
}
