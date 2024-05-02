package edu.oswego.cs.raft;

import edu.oswego.cs.Packets.*;
import edu.oswego.cs.Security.Encryption;
import edu.oswego.cs.game.Action;
import edu.oswego.cs.gui.MainFrame;
import edu.oswego.cs.stateMachine.ReplicatedStateMachine;

import java.io.IOException;
import java.net.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Raft {
   
   private final Timer heartBeatTimer = new Timer();
   private final Timer timeoutTimer = new Timer();
   private final Timer electionTimeoutTimer = new Timer();
   private final ConcurrentHashMap<String, Session> sessionMap = new ConcurrentHashMap<>();
   private final DatagramSocket serverSocket;
   public final AtomicReference<RaftMembershipState> raftMembershipState = new AtomicReference<>();
   public volatile boolean raftSessionActive;
   private final ConcurrentLinkedQueue<Action> queue = new ConcurrentLinkedQueue<>();
   private final Lock logLock = new ReentrantLock();
   private final List<Action> log = new ArrayList<>();
   private final ReplicatedStateMachine rsm;
   private final AtomicInteger lastActionConfirmed;
   private final AtomicBoolean gameActive = new AtomicBoolean(true);
   private volatile String userNameOfLeader;
   private volatile String clientUserName;
   private final RaftReceiver raftReceiver;
   private final AtomicBoolean keepReceiving = new AtomicBoolean(true);
   private final Object logConfirmerObject = new Object();
   private final Object followerLogMaintainerObject = new Object();
   private final ConcurrentHashMap<Integer, Action> actionMap = new ConcurrentHashMap<>();
   private final MainFrame mainFrame;
   private final Encryption encryption = new Encryption();
   private final AtomicInteger termCounter = new AtomicInteger(0);

   public Raft(int serverPort, String clientUserName, MainFrame mainFrame) throws SocketException {
      serverSocket = new DatagramSocket(serverPort);
      encryption.generateKeys();
      raftSessionActive = false;
      lastActionConfirmed = new AtomicInteger(-1);
      rsm = new ReplicatedStateMachine(log, lastActionConfirmed, gameActive, this, mainFrame, clientUserName);
      this.clientUserName = clientUserName;
      raftReceiver = new RaftReceiver(serverSocket, keepReceiving, this, clientUserName, logConfirmerObject, actionMap, followerLogMaintainerObject, log, encryption);
      this.mainFrame = mainFrame;
      raftReceiver.start();
   }

   public void startHeartBeat() {
      TimerTask task = new TimerTask() {
         public void run() {
            Action action = queue.poll();
            int termNum = -1;
            byte[] messageBytes;
            Packet packet;
            if (action != null) {
               logLock.lock();
               try {
                  log.add(action);
                  termNum = log.size() - 1;
                  sessionMap.get(userNameOfLeader).setGreatestActionConfirmed(termNum);

               } finally {
                  logLock.unlock();
               }
               packet = new LogCommandPacket(clientUserName, termNum, action.getUserName(), action.getCommand());
               messageBytes = packet.packetToBytes();
            } else {
               packet = new HeartbeatPacket(clientUserName, lastActionConfirmed.get());
               messageBytes = packet.packetToBytes();
            }

            sessionMap.forEachValue(Long.MAX_VALUE, (value) -> {
               // send to socket address
               if (value.getMembershipState() == RaftMembershipState.FOLLOWER && !value.getTimedOut()) {
                  SocketAddress socketAddress = value.getSocketAddress();
                  sendPacket(messageBytes, socketAddress);
               }
            });

            // in case a log confirmer notification is missed.
            synchronized (logConfirmerObject) {
               logConfirmerObject.notify();
            }
         }
      };
      long periodInMS = 10;
      heartBeatTimer.schedule(task, 0, periodInMS);
   }

   public void stopHeartBeat() {
      heartBeatTimer.cancel();
   }

   public void startTimeoutTimer() {
      TimerTask task = new TimerTask() {
         @Override
         public void run() {
            sessionMap.forEach(1, (key, value) -> {
               if (value.getMembershipState() == RaftMembershipState.FOLLOWER && !value.getTimedOut()) {
                  long nanoTime = System.nanoTime();
                  long disconnectThreshold = 200_000_000L;
                  long timeDifference = nanoTime - value.getLMRSTINT();
                  if (timeDifference > disconnectThreshold) {
                     value.setTimedOut(true);
                  }
               }
            });
         }
      };
      long periodInMS = 300L;
      timeoutTimer.schedule(task, 0, periodInMS);
   }

   public void stopTimeout() {
      timeoutTimer.cancel();
   }

   public void addSession(String username, Session session) {
      // only add a user if they don't exist in the map
      Session userSession = sessionMap.putIfAbsent(username, session);
      if (userSession != null && userSession.getMembershipState() == RaftMembershipState.PENDING_FOLLOWER) {
         userSession.setLMRSTINT(System.nanoTime());
         userSession.setMembershipState(RaftMembershipState.PENDING_FOLLOWER, RaftMembershipState.FOLLOWER);
      }
   }

   public void startRaftGroup() {
      encryption.generateSecretKey();
      raftMembershipState.set(RaftMembershipState.LEADER);
      raftSessionActive = true;
      this.userNameOfLeader = clientUserName;
      queue.add(new Action(userNameOfLeader, RaftAdministrationCommand.ADD_MEMBER.name + " " + userNameOfLeader + " " + System.nanoTime() + " " + serverSocket.getLocalAddress().toString().replace("/", "")));
      sessionMap.put(clientUserName, new Session(serverSocket.getLocalSocketAddress(), System.nanoTime(), raftMembershipState.get()));
      startHeartBeat();
      startTimeoutTimer();
      (new RaftLogConfirmer(logConfirmerObject, sessionMap, lastActionConfirmed, gameActive, clientUserName, serverSocket, log)).start();
      rsm.start();
   }

   public void exitRaft() {
      stopHeartBeat();
      stopTimeout();
      gameActive.set(false);
      synchronized (logConfirmerObject) {
         logConfirmerObject.notify();
      }
      synchronized (followerLogMaintainerObject) {
         followerLogMaintainerObject.notify();
      }
      keepReceiving.set(false);
      stopElectionTimeout();
      serverSocket.close();
      rsm.stop();
   }

   public void joinRaftGroup(SocketAddress groupAddress) {
      try {
         raftMembershipState.set(RaftMembershipState.FOLLOWER);
         ConnectionClientHelloPacket clientHelloPacket = new ConnectionClientHelloPacket(clientUserName, encryption.getPublicKey());
         byte[] connectHelloPacketBytes = clientHelloPacket.packetToBytes();
         DatagramPacket packet = new DatagramPacket(connectHelloPacketBytes, connectHelloPacketBytes.length, groupAddress);
         rsm.start();
         (new RaftFollowerLogMaintainer(raftMembershipState, logLock, log, lastActionConfirmed, followerLogMaintainerObject, actionMap)).start();
         serverSocket.send(packet);
      } catch (IOException e) {
         System.err.println("Something went wrong when trying to connect.");
         System.exit(1);
      }
   }

   public void commitAction(int index) {
      while (true) {
         int lastConfirmedIndex = lastActionConfirmed.get();
         if (index > lastConfirmedIndex) {
            lastActionConfirmed.compareAndSet(lastConfirmedIndex, index);
            synchronized (log) {
               log.notify();
            }
         } else {
            break;
         }
      }
   }

   public void sendMessage(String command) {
      // get leader and send message
      if (userNameOfLeader != null) {
         if (clientUserName.equals(userNameOfLeader)) {
            queue.add(new Action(clientUserName, command));
         } else {
            // send message to leader
            ReqCommandPacket reqCommandPacket = new ReqCommandPacket(clientUserName, command);
            byte[] packetBytes = reqCommandPacket.packetToBytes();
            if (getLeaderAddr() != null) {
               sendPacket(packetBytes, getLeaderAddr());
            }
         }
      }
   }

   public void addActionToQueue(Action action) {
      queue.add(action);
   }

   public void addToRaftQueue(String command) {
      queue.add(new Action(clientUserName, command));
   }

   public boolean addUser(String username, SocketAddress clientAddress) {
      Session client = sessionMap.putIfAbsent(username, new Session(clientAddress, System.nanoTime(), RaftMembershipState.PENDING_FOLLOWER));
      return client == null;
   }

   public void setLeader(String username, Session session) {
      userNameOfLeader = username;
      sessionMap.put(username, session);
   }

   public boolean userIsPending(String username) {
      Session session = sessionMap.get(username);
      return session != null && session.getMembershipState() == RaftMembershipState.PENDING_FOLLOWER;
   }

   public boolean addrIsLeader(SocketAddress socketAddress) {
      Session leaderSession = sessionMap.get(userNameOfLeader);
      return leaderSession != null && leaderSession.getSocketAddress().toString().equals(socketAddress.toString());
   }

   public void updateSessionTimeStamp(String username, SocketAddress socketAddress) {
      Session session = sessionMap.get(username);
      if (session != null ) {
         session.setLMRSTINT(System.nanoTime());
         // The user is actually still here.
         if (session.getTimedOut()) {
            session.setTimedOut(false);
         }
      }
   }

   public SocketAddress getLeaderAddr() {
      if (userNameOfLeader != null) {
         Session session = sessionMap.get(userNameOfLeader);
         if (session != null) {
            return session.getSocketAddress();
         }
      }
      return null;
   }

   public String getClientUserName() {
      return clientUserName;
   }

   public int getLastActionConfirmed() {
      return lastActionConfirmed.get();
   }

   public int getLogLength() {
      return log.size();
   }

   public Action getLogIndex(int i) {
      return log.get(i);
   }

   public void updateRaftFollowerGreatestConfirmedAction(String username, int actionNum) {
      Session session = sessionMap.get(username);
      if (session != null && session.getMembershipState() == RaftMembershipState.FOLLOWER) {
         session.setGreatestActionConfirmed(actionNum);
      }
   }

   // A method used for the worst case, log notification missed
   public void notifyLog() {
      synchronized (log) {
         log.notify();
      }
   }

   public int getLogPosition() {
      return log.size() - 1;
   }

   public void sendPacket(byte[] bytes, SocketAddress socketAddress) {
      byte[] encryptedBytes = encryption.encryptMessageWithSecretKey(bytes);
      if (encryptedBytes != null) {
         try {
            serverSocket.send(new DatagramPacket(encryptedBytes, encryptedBytes.length, socketAddress));
         } catch (IOException e) {
            System.err.println("An IOException is thrown when trying to send a heartbeat.");
         }
      }
   }

   public void startElectionTimeout() {
      TimerTask task = new TimerTask() {
         @Override
         public void run() {
            boolean runElection = true;
            if (userNameOfLeader != null) {
               Session leaderSession = sessionMap.get(userNameOfLeader);
               if (leaderSession != null) {
                  long timeStamp = leaderSession.getLMRSTINT();
                  long timeDiff = System.nanoTime() - timeStamp;
                  if (timeDiff < 300_000_000L) {
                     runElection = false;
                  }
               }
            }
            if (runElection) {
               System.out.println("Run an election");
            }
         }
      };
      long periodInMS = 50L;
      electionTimeoutTimer.schedule(task, 0, periodInMS);
   }

   public void stopElectionTimeout() {
      electionTimeoutTimer.cancel();
   }
}
