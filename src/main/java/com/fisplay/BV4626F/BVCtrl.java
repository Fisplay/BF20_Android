package com.fisplay.BV4626F;

/**
 * Created by jmantila on 12/8/14.
 * perustuu http://android.serverbox.ch/?p=370
 */

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


public class BVCtrl {
    protected static final String ACTION_USB_PERMISSION = "com.fisplay.BV4626F.USB";
    private static final String VID_PID = "0403:6001";
    public UsbDevice sDevice = null;
    public UsbDeviceConnection sConn;
    public UsbInterface sIf;
    public UsbEndpoint sEpIn = null;
    public UsbEndpoint sEpOut = null;
    private String escape = "1B5B";
    public String channels = "00001111";
    private Map relays = new HashMap();
    private Map relayStatus = new HashMap();
    private ArrayList<String> cmdBuffer = new ArrayList<String>();
    private boolean cmdRunning = false;
    private boolean connected = false;
    private int inputs = 0;

    private Thread thread;
    private Handler handler = new Handler();

    public interface Event {
      void InputChange(Integer ret);
    }

    public UsbManager usbman;

    public Settings settings;

    public BVCtrl(Context mContext, String channels) {
        relays.put("A", "41");
        relays.put("B", "42");
        relayStatus.put(0, "30");
        relayStatus.put(1, "31");

        //Handshake

        this.write("0D");
        //this.write("5D63");
        //Setting channels
        this.channels = channels;
        this.setChannels(channels);


        l("enumerating");
        this.usbman = (UsbManager) mContext.getSystemService(mContext.USB_SERVICE);

        HashMap<String, UsbDevice> devlist = this.usbman.getDeviceList();
        Iterator<UsbDevice> deviter = devlist.values().iterator();
        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, new Intent(
                ACTION_USB_PERMISSION), 0);

        while (deviter.hasNext()) {
            UsbDevice d = deviter.next();
            l("Found device: "
                    + String.format("%04X:%04X", d.getVendorId(),
                    d.getProductId()));
            if (String.format("%04X:%04X", d.getVendorId(), d.getProductId())
                    .equals(VID_PID)) {
                // we need to upload the hex file, first request permission
                l("Device under: " + d.getDeviceName());
                mContext.registerReceiver(mPermissionReceiver, new IntentFilter(
                        ACTION_USB_PERMISSION));
                if (!this.usbman.hasPermission(d)) {
                    this.usbman.requestPermission(d, pi);
                    l("requested permissions");

                } else
                   this.connect(d);
                break;
            }
        }
        l("no more devices found");
    }

    protected void connect(UsbDevice d) {
        l("Trying to connect");
        this.sDevice = d;
        this.sConn = this.usbman.openDevice(this.sDevice);
        l("Using " + String.format("%04X:%04X", this.sDevice.getVendorId(), this.sDevice.getProductId()));

        //Selvitetään interface, jos ei löydy, yritetään myöhemmin uudelleen (Broadcast receiver)
        if(!this.sConn.claimInterface(this.sDevice.getInterface(0), true))
            return;

        this.sConn.controlTransfer(0x40, 0, 0, 0, null, 0, 0);//reset
        this.sConn.controlTransfer(0x40, 0, 1, 0, null, 0, 0);//clear Rx
        this.sConn.controlTransfer(0x40, 0, 2, 0, null, 0, 0);//clear Tx
        this.sConn.controlTransfer(0x40, 0x03, 0x4138, 0, null, 0, 0);//baudrate 9600

        this.sIf = this.sDevice.getInterface(0);

        //Tunnistetaan In ja Out endpointit
        for(int i = 0; i < this.sIf.getEndpointCount(); i++){
            l("EP: "+String.format("0x%02X", this.sIf.getEndpoint(i).getAddress()));
            if(this.sIf.getEndpoint(i).getType() == UsbConstants.USB_ENDPOINT_XFER_BULK){
                l("Bulk Endpoint");
                if(this.sIf.getEndpoint(i).getDirection() == UsbConstants.USB_DIR_IN)
                    this.sEpIn = this.sIf.getEndpoint(i);
                else
                    this.sEpOut = this.sIf.getEndpoint(i);
            }else{
                l("Not Bulk");
            }

        }
        this.connected = true;
        //this.run();

        l("IN: " + this.sEpIn.toString());
        l("Out: " + this.sEpOut.toString());

        this.run();



        //Starting "read loop"
        /*thread = new Thread() {
            public void run() {
                // do something here
                readInputs();
                //Log.d("LOOP", "local Thread sleeping");
                handler.postDelayed(this, 200);
            }
        };
        handler.postDelayed(thread, 0);*/
    }

    public void write(String cmd) {
        l("Adding to cmdBuffer "+cmd + " length = " + this.cmdBuffer.size());
        this.cmdBuffer.add(cmd);
        this.run();
    }


    protected void run() {
       if(this.cmdRunning || this.connected==false || cmdBuffer.size()==0)
            return;

       this.cmdRunning = true;
       /*for (Iterator<String> iterator = cmdBuffer.iterator(); iterator.hasNext(); ) {
           String cmd = iterator.next();
           this.runCmd(cmd);
           iterator.remove();
       }*/
       ArrayList<String> remove = new ArrayList<String>();

       try {
           for (String cmd : cmdBuffer) {
               this.runCmd(cmd);
               remove.add(cmd);
           }

           cmdBuffer.removeAll(remove);
       } catch (ConcurrentModificationException e) {}

       this.cmdRunning = false;

    }

    protected void runCmd(String cmd) {

        //l("Writing " + cmd);
        byte[] bCmd = hexStringToByteArray(cmd);

        for(byte b: bCmd){//this is the main loop for transferring
            this.sConn.bulkTransfer(this.sEpOut, new byte[]{b}, 1, 1000);
            //Seems to work without sleep..
            l("Writing to port " + b);
            try {
               Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }



        }

        //Luetaan vastaus.
        byte[] buffer = new byte[5];

        int read;
        this.sConn.bulkTransfer(this.sEpIn, buffer, 5, 1000);
        //while( != -1) {}


        StringBuilder sb = new StringBuilder();
        for(byte b : buffer) {
            if(b!=17 && b!=96 && b>0) {
                l(b);
                sb.append((char) b);
            }

        }

        //päiviteään inputit
        if(cmd.equalsIgnoreCase(this.escape + "72")) {
            int sbi = this.inputs;
            try {
                //l(sb.toString());
                if(sb.toString()!="")
                    sbi = Integer.parseInt(sb.toString());
                else sbi = 0;

                if (this.inputs != sbi) {
                    this.inputs = sbi;
                    //l(sbi);
                    inputChange();
                }
            } catch (Exception e) {
                l("Wrong input! Is channels set right?" + e.getMessage());
            }
            //l(sbi);


        }

    }

    public void setChannels(String channels) {

        String cH = String.format("%2s", Integer.toHexString(Integer.parseInt(channels, 2))).replace(" ", "0").toUpperCase();


        String chan = Integer.toString(Integer.parseInt(channels, 2));
        StringBuilder sb = new StringBuilder();
        for (char ch :  chan.toCharArray()) {
            sb.append(Integer.toHexString(ch));

        }
        l("dec " + sb.toString());


        l("Setting chanels to inputs " + channels + " = " + sb.toString());

        this.write(this.escape + sb.toString() + "73");
    }


    public void relay(String r, int status) {
        //l(System.out.printf("%02X", Integer.toHexString(status)));
        l("Setting relay " + r + " to " +status);
        this.write(this.escape + this.relayStatus.get(status) + this.relays.get(r) );
    }


    public void readInputs() {
       //Max 8 inputtia voidaan lukea, jos käytössä.

        //l("Reading inputs!");


        /*while(this.cmdRunning==true) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
           l("WAITING");
        }*/
        this.write(this.escape + "72");

    }

    //Need override
    public void inputChange() {
        l("Changed");
        l(this.inputs);
        l("input 1 " + checkInput(1));
        l("input 2 " + checkInput(2));
        l("input 3 " + checkInput(3));
        l("input 4 " + checkInput(4));
        l("input 5 " + checkInput(5));
        l("input 6 " + checkInput(6));
        l("input 7 " + checkInput(7));
        l("input 8 " + checkInput(8));



    }

    public boolean checkInput(int no) {
       double check = Math.pow(2, no-1);

       //l("test " + check);
       //l("test" + ((int) check));
        //l("checkval1 " + Math.pow((double)no, 2) + " no " + no);
        //l("inputs " + inputs + "check" + check);
        //l("checkval " + check);
        return (this.inputs & ((int) check)) > 0;
    }

    public void setOutput(int ch, boolean mode) {
      //Max 8 kanavaa voidaan asettaa, jos käytössä

    }

    public int adc(int ch) {
      // 1-3 channels, return 0-1023

      return 1;
    }

    public void dac(int ch, int num) {
        //0-63


    }





    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private final LaunchReceiver mPermissionReceiver = new LaunchReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                if (!intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    e("Permission not granted :(");
                } else {
                    l("Permission granted");
                    UsbDevice dev = (UsbDevice) intent
                            .getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (dev != null) {
                        if (String.format("%04X:%04X", dev.getVendorId(),
                                dev.getProductId()).equals(VID_PID)) {
                            connect(dev);

                        }
                    } else {
                        e("device not present!");
                    }
                }
            }
        }
    };

    private static void l(Object s) {
        Log.d("FTDI_USB", ">==< " + s.toString() + " >==<");
    }

    private static void e(Object s) {
        Log.e("FTDI_USB", ">==< " + s.toString() + " >==<");
    }

    public int getInputs() {
        return this.inputs;
    }
}
