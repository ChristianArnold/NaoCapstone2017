//////////////////////////////////////////////////////////////////////////////
//                                                                          //
// This software is a work of the U.S. Government. It is not subject to     //
// copyright protection and is in the public domain. It may be used as-is   //
// or modified and re-used. The author and the Air Force Research           //
// Laboratory would appreciate credit if this software or parts of it are   //
// used or modified for re-use.                                             //
//                                                                          //
//////////////////////////////////////////////////////////////////////////////

import java.awt.*;

import java.awt.event.*;
import java.io.*;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.aldebaran.qi.*;
import com.aldebaran.qi.helper.*;
import com.aldebaran.qi.helper.proxies.*;

import common.*;

/****************************************************************************
 * This class tests the Naomark capabilities of Rufus.
 *
 * @author Craig Cox (Craig.Cox.1@us.af.mil, c.afrl.rhxs@reacomp.com)
 * 
 * Edits have been made to implement functionalities for the USAFA/DFBL
 * human-machine teaming capstone project. These edits are made by 
 * Jacob Taylor (jacob.taylor.14@us.af.mil, jacobrtaylor91@gmail.com).
 ****************************************************************************/
public class NaoMarks extends JFrame
    implements ExceptionHandler, HeadControl, TextPanel.Listener {
  public static final String VERSION = "0.2";

  //CHANGES: Changed robot address to not be final so it can be changed by getIP()
  private static final int    DEFAULT_ROBOT_PORT = 9559;
  private static String DEFAULT_ROBOT_ADDRESS = "192.168.0.101";

  private final ArrayList<String> QUEUE = new ArrayList<>();
  private final Object            DISPLAY_LOCK = "";

  private boolean        m_bLandmarkDetected = false;
  private float          m_fHeadPitch = -0.17f;
  private float          m_fHeadYaw = 0.00f;
  private ALMotion       m_srvcMotion;
  private ALTextToSpeech m_srvcTextToSpeech;
  private JTextArea      m_taStatus;
  private Session        m_session;
  private TextPanel      m_pnlText;
  private VisionPanel    m_pnlVision;

  public NaoMarks() {
	super("NaoMarks");
	
	//CHANGES: Added getIP() function
	DEFAULT_ROBOT_ADDRESS = getIP();
	// End change

	buildGui();
  }

  //CHANGES: Added getter for the status area
  public JTextArea getStatusArea() {
	  return this.m_taStatus;
  }
  
  //CHANGES: Added getIP() function
  public String getIP() {
		final JFrame frame = new JFrame();
		String IP = new String();
		
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		
		IP = JOptionPane.showInputDialog(null, "Enter robot IP: ", DEFAULT_ROBOT_ADDRESS);
		
		frame.pack();
		frame.setVisible(true);
		return IP;
  }
  // End change
  
  /**
   * Required by the TextPanel.Listener interface.
   */
  @Override
  public void hearText(String sText) {
    send(sText);
  }

  public float getHeadPitch() {
    return m_fHeadPitch;
  }

  public float getHeadYaw() {
    return m_fHeadYaw;
  }

  /**
   * Required by the HeadControl interface.
   */
  @Override
  public void setHeadAngles(float[] fHeadAngles) {
    // Save the desired head angles;
    m_fHeadYaw = fHeadAngles[0];
    m_fHeadPitch = fHeadAngles[1];

    // Move the head to the desired angles.
    moveHead((m_pnlVision.isScanning())
             ? 0.05f
             : 0.15f);
  }

  /**
   * Required by the HeadControl interface.
   */
  @Override
  public float[] getHeadAngles() {
    return new float[] { m_fHeadYaw,
                         m_fHeadPitch };
  }

  /**
   * Initialize communications with Rufus.
   */
  public void initialize(Session session) {
	try {
      // Save the passed session.
      m_session = session;

      // Create required services.
      m_srvcMotion       = new ALMotion(m_session);
      m_srvcTextToSpeech = new ALTextToSpeech(session);

      // Set the autonomous move background strategy to none to prevent Rufus
      // from moving back to some default posture after a new posture is
      // commanded.
      new ALAutonomousMoves(m_session).setBackgroundStrategy("none");

      // Stop basic awareness to keep Rufus from turning his head to look at
      // a sound source.
      new ALBasicAwareness(m_session).stopAwareness();

      // Have Rufus stand up, if not already standing.
      new Thread(new Runnable() {
        @Override
        public void run() {
          try {
        	  // CHANGES: Changed init posture to resting
        	  new ALMotion(m_session).rest();
          } catch (Exception e) {
            handleException(e);
          }
        }
      }).start();

      // Have Rufus slowly look at the current head angles (straight forward).
      moveHead(0.05f);

      // Subscribe to changes in the LandmarkDetected memory location.
      new ALMemory(m_session).subscribeToEvent("LandmarkDetected",
                                               getLandmarkDetectedCallback());

      // Start the image retrieval thread.
      startImageRetrievalThread();

      // Start a thread to show some help if no landmarks are found.

      //CHANGES: Commented out the help thread, because it's annoying.
      //startLandmarkHelpThread();

      // Start a thread to move the head to scan for landmarks.
      startScanThread();

      // Start the thread to allow Rufus to speak.
      startSpeechThread();
    } catch (Exception e) {
      handleException(e);
    }
  }

  private void startImageRetrievalThread()
      throws Exception {
    final ALVideoDevice VIDEO;
    final String        MODULE_NAME = "IMAGE_RETRIEVAL";
    final String        SUBSCRIPTION_ID;

    Thread thread;

    // Create a video session.
    VIDEO = new ALVideoDevice(m_session);

    // Unsubscribe from any instances that may have been subscribed to on a
    // previous run of this application.
    VIDEO.unsubscribeAllInstances(MODULE_NAME);

    // Subscribe to the video using an RGB color space and 5 frames per
    // second.
    SUBSCRIPTION_ID = VIDEO.subscribe(MODULE_NAME,
                                      0,
                                      11,
                                      5);

    // Create a thread to display received images.
    thread = new Thread(new Runnable() {
      @Override
      public void run() {
        int       nLastTime = -1;
        int       nTime;
        ArrayList list;
        Object    o;

        while (true) {
          try {
            // Get image data from the robot.
            o = VIDEO.getImageRemote(SUBSCRIPTION_ID);

            if (   null != o
                && o instanceof ArrayList) {
              // If valid, the image data will be in an array list.
              list = (ArrayList) o;

              // Array contains image information.
              //  0: Width
              //  1: Height
              //  2: Number of layers
              //  3: ColorSpace
              //  4: Timestamp (highest 32 bits)
              //  5: Timestamp (lowest 32 bits)
              //  6: Image data array of size height * width * nblayers
              //  7: CameraID
              //  8: Left angle
              //  9: Top angle
              // 10: Right angle
              // 11: Bottom angle

              // Get the low bits of the timestamp.
              nTime = ((Integer) list.get(5)).intValue();

              // Only process the image if the low bits of the timestamp are
              // different than the last time an image was processed.
              // Otherwise, we have retrieved the same image as last time.
              if (nTime != nLastTime) {
                // Grab the lock for the display.
                synchronized (DISPLAY_LOCK) {
                  // Update the display panel with the image data, consisting
                  // of the image width/height, pixel data, and camera
                  // left/top/right/bottom angles.
                  m_pnlVision.updateImageData(
                      ((Integer) list.get(0)).intValue(),
                      ((Integer) list.get(1)).intValue(),
                      ((java.nio.ByteBuffer) list.get(6)).array(),
                      ((Float) list.get(8)).floatValue(),
                      ((Float) list.get(9)).floatValue(),
                      ((Float) list.get(10)).floatValue(),
                      ((Float) list.get(11)).floatValue());
                }
              }
            }

            // Release the image that was retrieved.
            VIDEO.releaseImage(SUBSCRIPTION_ID);

            try { Thread.sleep(100); } catch (InterruptedException e) {}
          } catch (Exception e) {
            handleException(e);
          }
        }
      }
    }, "ImageRetrievalThread");

    thread.setDaemon(true);

    thread.start();
  }

  private void startScanThread() {
    Thread thread;

    // Start a thread to have Rufus move his head to scan for landmarks.
    thread = new Thread(new Runnable() {
      @Override
      public void run() {
        double dNextTheta;
        int    nCurrentPitch;
        int    nCurrentYaw;
        int    nDeltaPitch;
        int    nDeltaYaw;
        int    nNextPitch;
        int    nNextYaw;
        int[]  nSliderValues;

        while (true) {
          try {
            // Check to see if we should be scanning.
            if (m_pnlVision.isScanning()) {
              // We should be scanning. Get the current yaw/pitch values.
              nSliderValues = m_pnlVision.getSliderValues();
              nCurrentYaw = nSliderValues[0] - 50;
              nCurrentPitch = nSliderValues[1] - 50;

              // Theta is the angle around the circle that Rufus scans.
              // Determine the next desired theta angle by getting the current
              // angle and adding 0.1 radians.
              dNextTheta = 0.1
                           + Math.atan2(nCurrentPitch,
                                        nCurrentYaw);

              // Determine the desired next yaw/pitch values to get to the
              // desired theta angle with a radius of 40 slider counts.
              nNextYaw = (int) Math.round(40 * Math.cos(dNextTheta));
              nNextPitch = (int) Math.round(40 * Math.sin(dNextTheta));

              // To slow down the head movement, limit changes in desired
              // yaw/pitch to a difference of one slider count from the
              // current yaw/pitch.
              nDeltaYaw = limitAbs(nNextYaw - nCurrentYaw,
                                   1);
              nNextYaw = nCurrentYaw + nDeltaYaw;
              nDeltaPitch = limitAbs(nNextPitch - nCurrentPitch,
                                     1);
              nNextPitch = nCurrentPitch + nDeltaPitch;

              // Set slider values appropriately.
              m_pnlVision.setSliderValues(new int[] { nNextYaw + 50,
                                                      nNextPitch + 50 });
            }

            Thread.sleep(100);
          } catch (InterruptedException e) {
          }
        }
      }
    }, "ScanThread");

    thread.setDaemon(true);

    thread.start();
  }

  private int limitAbs(int nValue,
                       int nAbsoluteLimit) {
    // Constrain a value to some absolute limit.
    return (int) (Math.signum(nValue)
                  * Math.min(Math.abs(nValue),
                             Math.abs(nAbsoluteLimit)));
  }

  private void moveHead(float fMotorSpeed) {
    try {
      m_srvcMotion.setAngles(
          new Tuple2<String,String>("HeadYaw",
                                    "HeadPitch"),
          new Tuple2<Float,Float>(m_fHeadYaw,
                                  m_fHeadPitch),
          fMotorSpeed);
    } catch (Exception e) {
      handleException(e);
    }
  }

  private void startLandmarkHelpThread() {
    Thread thread;

    thread = new Thread(new Runnable() {
      @Override
      public void run() {
        // Wait 10 seconds.
        try {
          Thread.sleep(10000);
        } catch (Exception e) {
        }

        // If not landmark has been detected, the landmark detection code may
        // not be loaded, so display a help message.
        if (!m_bLandmarkDetected) {
          String   sMessage = "";
          String[] sLines;

          sLines  = new String[]
              { 
              "No NaoMarks have been found. If NaoMarks",
              "should be visible in the robot's field of",
              "view, try the following steps:",
              "",
              "  1. Run the Nao monitor application.",
              "  2. Click on the word \"Camera\".",
              "  3. Double-click the Nao robot at 10.0.1.240.",
              "  4. Select the \"mark detection\" checkbox.",
              "  5. Close the monitor window." };

          for (String s : sLines) {
            sMessage += s + "\n";
          }

          JOptionPane.showMessageDialog(NaoMarks.this,
                                        sMessage,
                                        "No NaoMarks found!",
                                        JOptionPane.WARNING_MESSAGE);
        }
      }
    }, "LandmarkHelpThread");

    thread.setDaemon(true);

    thread.start();
  }

  @Override
  public void handleException(Exception e)
  {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    e.printStackTrace(new PrintStream(baos));

    m_taStatus.append(baos.toString());
    m_taStatus.setCaretPosition(m_taStatus.getText().length());
  }
  
  //CHANGES: Added a logging function to handle timestamping and logging behavior runs
  private void logButton(String s){
	    SimpleDateFormat df = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss");
	    Timestamp ts = new Timestamp(System.currentTimeMillis());
	    String text = df.format(ts) + ": " + s + "\n";
	    m_taStatus.append(text);
	    m_taStatus.setCaretPosition(m_taStatus.getDocument().getLength());
  }
  
  //CHANGES: Added 'addButtons' function
  private JPanel addButtons(JPanel sp1) {
	    JButton b1 = new JButton("1 Correct");
	    b1.setVerticalTextPosition(AbstractButton.CENTER);
	    b1.setHorizontalTextPosition(AbstractButton.LEADING);
	    // Use mnemonic for Alt hotkeys
	    b1.setMnemonic(KeyEvent.VK_1);
	    b1.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				try {
					new ALBehaviorManager(m_session).runBehavior("DFBL_1_Correct");
					logButton("One correct.");
		          } catch (Exception ex) {
		            handleException(ex);
		        }
			}
		});
	    
	    JButton b2 = new JButton("2 Correct");
	    b2.setVerticalTextPosition(AbstractButton.CENTER);
	    b2.setHorizontalTextPosition(AbstractButton.LEADING);
	    // Use mnemonic for Alt hotkeys
	    b2.setMnemonic(KeyEvent.VK_2);
	    b2.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				try {
					new ALBehaviorManager(m_session).runBehavior("DFBL_2_Correct");
					logButton("Two correct.");
		          } catch (Exception ex) {
		            handleException(ex);
		        }
			}
		});
	    
	    JButton b3 = new JButton("3 Correct");
	    b3.setVerticalTextPosition(AbstractButton.CENTER);
	    b3.setHorizontalTextPosition(AbstractButton.LEADING);
	    // Use mnemonic for Alt hotkeys
	    b3.setMnemonic(KeyEvent.VK_3);
	    b3.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				try {
					new ALBehaviorManager(m_session).runBehavior("DFBL_3_Correct");
					logButton("Three correct.");
		          } catch (Exception ex) {
		            handleException(ex);
		        }
			}
		});
	    
	    JButton b4 = new JButton("4 Correct");
	    b4.setVerticalTextPosition(AbstractButton.CENTER);
	    b4.setHorizontalTextPosition(AbstractButton.LEADING);
	    // Use mnemonic for Alt hotkeys
	    b4.setMnemonic(KeyEvent.VK_4);
	    b4.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				try {
					new ALBehaviorManager(m_session).runBehavior("DFBL_4_Correct");
					logButton("Four correct.");
		          } catch (Exception ex) {
		            handleException(ex);
		        }
			}
		});
	    
	    JButton b5 = new JButton("0 Correct");
	    b5.setVerticalTextPosition(AbstractButton.CENTER);
	    b5.setHorizontalTextPosition(AbstractButton.LEADING);
	    // Use mnemonic for Alt hotkeys
	    b5.setMnemonic(KeyEvent.VK_5);
	    b5.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				try {
					new ALBehaviorManager(m_session).runBehavior("DFBL_0_Correct");
					logButton("None correct.");
		          } catch (Exception ex) {
		            handleException(ex);
		        }
			}
		});
	    
	    JButton b6 = new JButton("Yes");
	    b6.setVerticalTextPosition(AbstractButton.CENTER);
	    b6.setHorizontalTextPosition(AbstractButton.LEADING);
	    // Use mnemonic for Alt hotkeys
	    b6.setMnemonic(KeyEvent.VK_Y);
	    b6.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				try {
					new ALBehaviorManager(m_session).runBehavior("DFBL_Yes");
					logButton("Yes.");
		          } catch (Exception ex) {
		            handleException(ex);
		        }
			}
		});

	    JButton b7 = new JButton("No");
	    b7.setVerticalTextPosition(AbstractButton.CENTER);
	    b7.setHorizontalTextPosition(AbstractButton.LEADING);
	    // Use mnemonic for Alt hotkeys
	    b7.setMnemonic(KeyEvent.VK_N);
	    b7.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				try {
					new ALBehaviorManager(m_session).runBehavior("DFBL_No");
					logButton("No.");
		          } catch (Exception ex) {
		            handleException(ex);
		        }
			}
		});

	    // Add all buttons to the sub-panel and return it

	    sp1.add(b1);
	    sp1.add(b2);
	    sp1.add(b3);
	    sp1.add(b4);
	    sp1.add(b5);
	    sp1.add(b6);
	    sp1.add(b7);
	    
	    return sp1;
  }
  // end change
  
  private void buildGui() {
    Container   cp;
    JPanel      p1;
    JScrollPane jsp;

    createMenu();

    cp = getContentPane();
    cp.setLayout(new BorderLayout(5,
                                  5));
    ((JPanel) cp).setBorder(BorderFactory.createEmptyBorder(5,
                                                            5,
                                                            5,
                                                            5));

    m_pnlVision = new VisionPanel((HeadControl) this,
                                  DISPLAY_LOCK);
    cp.add(m_pnlVision,
           BorderLayout.CENTER);

    p1 = new JPanel(new BorderLayout(0,
                                     5));
    cp.add(p1,
           BorderLayout.SOUTH);

    // CHANGES: Added 'this' to TextPanel constructor
    m_pnlText = new TextPanel(this);
    m_pnlText.addListener(this);
    p1.add(m_pnlText,
           BorderLayout.CENTER);

    //CHANGES: Added buttons to the UI
    // Add buttons to p1 in this area.  
    JPanel sp1 = new JPanel();
    sp1 = addButtons(sp1);

    // Add the sub-panel to p1
    p1.add(sp1, BorderLayout.NORTH);
    // end change
    
    m_taStatus = new JTextArea(5,
                               40);
    m_taStatus.setEditable(false);
    m_taStatus.setLineWrap(true);
    m_taStatus.setWrapStyleWord(true);
    jsp = new JScrollPane(m_taStatus,
                          JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                          JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    p1.add(jsp,
           BorderLayout.SOUTH);

    pack();

    GraphicsUtil.center(this);

    setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

    addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent event) {
        new Thread(new Runnable() {
          @Override
          public void run() {
        	//CHANGES: Added writer object
          	BufferedWriter writer = null;
          	// end change
            try {
            	//CHANGES: Added logging of the status panel to a text file on close.            	
            	UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            	JFileChooser choochoo = new JFileChooser();
            	File wd = new File(System.getProperty("user.dir"));
            	choochoo.setCurrentDirectory(wd);
            	choochoo.setFileFilter(new FileNameExtensionFilter("*.txt", "txt"));
            	choochoo.setDialogType(JFileChooser.SAVE_DIALOG);
            	int rval = choochoo.showOpenDialog(null);
            	if (rval == JFileChooser.APPROVE_OPTION){
                	File logfile = choochoo.getSelectedFile();

                	if (!logfile.toString().endsWith(".txt")){
                		logfile = new File(logfile.getPath() + ".txt");
                	}
                	
                	writer = new BufferedWriter(new FileWriter(logfile));
                	// Get the contents of the status panel
                	String content = m_taStatus.getText();
                	content = content.replaceAll("(?!\\r)\\n", "\r\n");
                	// Write the contents of the status panel to the file
                	writer.write(content);
            	}           	
            	//end change
            	
              // Have Rufus return to the stand posture when the window is
              // closed.
            	// CHANGES: Changed position to resting to protect motors
            	new ALMotion(m_session).rest();
            } catch (Exception e) {
              // Ignore errors since the application is already closing.
            } finally {
              //CHANGES: Added line for closing the writer
              try{ 
            	  writer.close();
              } catch (Exception e){
              }
              // end change
              System.exit(0);
            }
          }
        }).start();
      }
    });
  }
  
  private EventCallback<Object> getLandmarkDetectedCallback() {
    return new EventCallback<Object>() {
      // When a landmark is detected, process its data.
      @Override
      public void onEvent(Object o) throws InterruptedException,
                                           CallError {
        processLandmarkData(o);
      }
    };
  }

  private void createMenu()
  {
    JCheckBoxMenuItem cbmi;
    JMenu             menu;
    JMenuBar          menuBar;
    String            sVersion;

    setJMenuBar(menuBar = new JMenuBar());

    menuBar.add(menu = new JMenu("Vision"));

    cbmi = new JCheckBoxMenuItem("Scan");
    cbmi.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent event) {
        m_pnlVision.setScan(
            ((JCheckBoxMenuItem) event.getSource()).isSelected());
      }
    });
    menu.add(cbmi);
    
    // CHANGES: Adding postures menubar item
    menuBar.add(menu = new JMenu("Postures"));
    JMenuItem crouch = new JMenuItem("Crouch");
    crouch.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
				new ALMotion(m_session).rest();
				logButton("Went to resting position.");
			} catch (Exception e) {
				handleException(e);
			}
    	}
    });

    JMenuItem stand = new JMenuItem("Stand");
    stand.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
              new ALRobotPosture(m_session).goToPosture("Stand",
                                                        0.25f);
              logButton("Went to standing position.");
            } catch (Exception e) {
              handleException(e);
            }
    	}
    });
    
    JMenuItem sit = new JMenuItem("Sit");
    sit.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
              new ALRobotPosture(m_session).goToPosture("Sit",
                                                        0.25f);
              logButton("Went to sitting position.");
            } catch (Exception e) {
              handleException(e);
            }
    	}
    });
    
    JMenuItem sitrelaxed = new JMenuItem("Sit Relaxed");
    sitrelaxed.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
              new ALRobotPosture(m_session).goToPosture("SitRelax",
                                                        0.25f);
              logButton("Went to relaxed sitting position.");
            } catch (Exception e) {
              handleException(e);
            }
    	}
    });
    
    menu.add(crouch);
    menu.add(stand);
    menu.add(sit);
    menu.add(sitrelaxed);
    // End change
    
    //CHANGES: Added prompts menu item
    menuBar.add(menu = new JMenu("Prompts"));
    JMenuItem p1 = new JMenuItem("Prompt 1");
    // Create keystroke item for use in setAccelerator (hotkey)
    KeyStroke c1 = KeyStroke.getKeyStroke(KeyEvent.VK_1, Toolkit.getDefaultToolkit ().getMenuShortcutKeyMask());
    p1.setAccelerator(c1);
    p1.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
				new ALBehaviorManager(m_session).runBehavior("DFBL_Beh_1");
				logButton("Ran prompt 1.");
			} catch (Exception e) {
				handleException(e);
			}
    	}
    });
    
    JMenuItem p2 = new JMenuItem("Prompt 2");
    // Create keystroke item for use in setAccelerator (hotkey)
    KeyStroke c2 = KeyStroke.getKeyStroke(KeyEvent.VK_2, Toolkit.getDefaultToolkit ().getMenuShortcutKeyMask());
    p2.setAccelerator(c2);
    p2.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
				new ALBehaviorManager(m_session).runBehavior("DFBL_Beh_2");
				logButton("Ran prompt 2.");
			} catch (Exception e) {
				handleException(e);
			}
    	}
    });
    
    JMenuItem p3 = new JMenuItem("Prompt 3");
    // Create keystroke item for use in setAccelerator (hotkey)
    KeyStroke c3 = KeyStroke.getKeyStroke(KeyEvent.VK_3, Toolkit.getDefaultToolkit ().getMenuShortcutKeyMask());
    p3.setAccelerator(c3);
    p3.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
				new ALBehaviorManager(m_session).runBehavior("DFBL_Beh_3");
				logButton("Ran prompt 3.");
			} catch (Exception e) {
				handleException(e);
			}
    	}
    });
    
    JMenuItem p4 = new JMenuItem("Prompt 4");
    // Create keystroke item for use in setAccelerator (hotkey)
    KeyStroke c4 = KeyStroke.getKeyStroke(KeyEvent.VK_4, Toolkit.getDefaultToolkit ().getMenuShortcutKeyMask());
    p4.setAccelerator(c4);
    p4.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
				new ALBehaviorManager(m_session).runBehavior("DFBL_Beh_4");
				logButton("Ran prompt 4.");
			} catch (Exception e) {
				handleException(e);
			}
    	}
    });
    
    JMenuItem p5 = new JMenuItem("Prompt 5");
    // Create keystroke item for use in setAccelerator (hotkey)
    KeyStroke c5 = KeyStroke.getKeyStroke(KeyEvent.VK_5, Toolkit.getDefaultToolkit ().getMenuShortcutKeyMask());
    p5.setAccelerator(c5);
    p5.addActionListener(new ActionListener() {
    	@Override
    	public void actionPerformed(ActionEvent event) {
    		try {
				new ALBehaviorManager(m_session).runBehavior("DFBL_Beh_5");
				logButton("Completed study.");
			} catch (Exception e) {
				handleException(e);
			}
    	}
    });
    
    menu.add(p1);
    menu.add(p2);
    menu.add(p3);
    menu.add(p4);
    menu.add(p5);
    // end change
    
    menuBar.add(Box.createHorizontalGlue());

    menuBar.add(menu = new JMenu("Help"));

    sVersion = VERSION + " (git hash " + GitBuild.GIT_HASH + ")";

    menu.add(GraphicsUtil.createAboutInfoMenuItem("About " + getTitle(),
                                                  this,
                                                  sVersion,
                                                  null,
                                                  GraphicsUtil.CONTACTS));
  }

  private void processLandmarkData(Object o) {
    float[][] fLandmarkData = null;
    int       nIndex = -1;
    ArrayList al1;
    ArrayList al2;

    try {
      al1 = (ArrayList) ((ArrayList) o).get(1);

      fLandmarkData = new float[al1.size()][3];

      for (Object o2 : al1) {
        nIndex++;

        al2 = (ArrayList) ((ArrayList) o2).get(0);
        fLandmarkData[nIndex][0] = ((Float) al2.get(1)).floatValue();
        fLandmarkData[nIndex][1] = ((Float) al2.get(2)).floatValue();

        al2 = (ArrayList) ((ArrayList) o2).get(1);
        fLandmarkData[nIndex][2] = ((Integer) al2.get(0)).intValue();
      }

      m_bLandmarkDetected = true;
    } catch (Exception e) {
      fLandmarkData = null;
    }

    m_pnlVision.updateLandmarkData(fLandmarkData);
  }

  private void startSpeechThread() {
    Thread thread;

    thread = new Thread(new Runnable() {
      @Override
      public void run() {
        String sText = null;

        while (true) {
          synchronized (QUEUE) {
            sText = null;

            if (!QUEUE.isEmpty()) {
              sText = QUEUE.remove(0);
            } else {
              try {
                QUEUE.wait();
              } catch (InterruptedException e) {
              }
            }
          }

          if (null != sText) {
            try {
              m_srvcTextToSpeech.say(sText);
            } catch (Exception e) {
              handleException(e);
            }
          }
        }
      }
    }, "TextToSpeech");
    thread.setDaemon(true);

    thread.start();

    // Trigger an action event on the text panel to make Rufus speak the
    // current text.
    m_pnlText.actionPerformed(null);
  }


  private void send(String sText) {
    synchronized (QUEUE) {
      QUEUE.add(sText);

      QUEUE.notifyAll();
    }
  }

  private void drillDown(Object o) {
    drillDown("",
              o);
  }

  private void drillDown(String sPrefix,
                         Object o) {
    System.out.print(sPrefix + o.getClass().getName());

    if (o instanceof ArrayList) {
      System.out.println();

      for (Object o2 : (ArrayList) o) {
        drillDown("  " + sPrefix,
                  o2);
      }
    } else {
      System.out.println(": " + o);
    }
  }

  public static void main(String[] args) throws Exception
  {
    Application application;
    NaoMarks    frame;
    String      sUrl;

    frame = new NaoMarks();
    frame.setVisible(true);

    try {
      // Create a new Application.
      sUrl = "tcp://" + DEFAULT_ROBOT_ADDRESS
             + ":" + DEFAULT_ROBOT_PORT;
      application = new Application(args,
                                    sUrl);

      // Start the application, which creates a session and connects it to the
      // robot.
      application.start();

      // Initialize the frame with the created session.
      frame.initialize(application.session());
    } catch (Exception e) {
      frame.handleException(e);
    }
  }
}
