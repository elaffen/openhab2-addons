/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * ----------------------------------------------------------------------------
 *
 *  This ARDUINO application listening data from Nibe F1145/F1245 heat pumps (RS485 bus)
 *  and send valid frames to configurable IP/port address by UDP packets.
 *  Application also acknowledge the valid packets to heat pump.
 *
 *  Ethernet and RS-485 arduino shields are required.
 *
 *  Serial settings: 9600 baud, 8 bits, Parity: none, Stop bits 1
 *
 *  MODBUS module support should be turned ON from the heat pump.
 *
 *  Frame format:
 *  +----+----+------+-----+-----+----+----+-----+
 *  | 5C | 00 | ADDR | CMD | LEN |  DATA   | CHK |
 *  +----+----+------+-----+-----+----+----+-----+
 *
 *            |------------ CHK -----------|
 *
 *  Address:
 *    0x16 = SMS40
 *    0x19 = RMU40
 *    0x20 = MODBUS40
 *
 *  Checksum: XOR
 *
 *  When valid data is received (checksum ok),
 *  ACK (0x06) should be sent to the heat pump.
 *  When checksum mismatch,
 *  NAK (0x15) should be sent to the heat pump.
 *
 *  If heat pump does not receive acknowledge in certain time period,
 *  pump will raise an alarm and alarm mode is activated.
 *
 *  Author: pauli.anttila@gmail.com
 *
 *
 *  2.11.2013   v1.00   Initial version.
 *  3.11.2013   v1.01
 *  27.6.2014   v1.02   Fixed compile error and added Ethernet initialization delay.
 *  29.6.2015   v2.00   Bidirectional support.
 */

#include <SPI.h>
#include <Ethernet.h>
#include <EthernetUdp.h>
#include <avr/wdt.h>

// ######### CONFIGURATION #######################

// Media Access Control address for the Ethernet shield
byte mac[] = { 0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0xED };

// IP address for the shield
byte ip[] = { 192, 168, 1, 50 };

// target IP address and UDP port
const IPAddress target_ip(192, 168, 1, 28);
const unsigned int target_port = 9999;

// local port to listen
const unsigned int local_udp_port = 9999;

// delay before initialize Ethernet in seconds
const int init_eth_timer = 60;

// enable support for different devices
boolean enable_SMS40 = true;
boolean enable_RMU40 = true;
boolean enable_MODBUS40 = true;

// enable ack sending to nibe
boolean ack_SMS40 = true;
boolean ack_RMU40 = true;
boolean ack_MODBUS40 = true;

// ######### VARIABLES #######################

// direction change pin for RS-485 port
#define directionPin  2

// state machine states
enum e_state
{
  STATE_WAIT_START,
  STATE_WAIT_DATA,
  STATE_OK_MESSAGE_RECEIVED,
  STATE_TOKEN_RECEIVED,
  STATE_CRC_FAILURE,
};

e_state state = STATE_WAIT_START;

EthernetUDP udp;

// message buffer for RS-485 communication
#define MAX_DATA_LEN 255
byte buffer[MAX_DATA_LEN];

byte index = 0;
boolean eth_initialized = false;

#define START_CHAR      0x5C

#define ADDR_SMS40      0x16
#define ADDR_RMU40      0x19
#define ADDR_MODBUS40   0x20

// ######### DEBUG #######################

// enable debug printouts, listen printouts e.g. via netcat (nc -l -u 50000)
#define ENABLE_DEBUG

#ifdef ENABLE_DEBUG
#define DEBUG_PRINT(level, message) if (verbose >= level) { debugPrint(message); }
#define DEBUG_PRINTDATA(level, message, data) if (verbose >= level) { sprintf(debug_buf, message, data); debugPrint(debug_buf); }
#define DEBUG_PRINTARRAY(level, data, len) if (verbose >= level) { for (int i = 0; i < len; i++) { sprintf(debug_buf, "%02x", data[i]); debugPrint(debug_buf); }}
#else
#define DEBUG_PRINT(level, message)
#define DEBUG_PRINTDATA(level, message, data)
#define DEBUG_PRINTARRAY(level, data, len)
#endif

#ifdef ENABLE_DEBUG
char verbose = 2;
char debug_buf[100];
unsigned int debug_udp_port = 50000;

void debugPrint(char* data)
{
  if (eth_initialized) {
    udp.beginPacket(target_ip, debug_udp_port);
    udp.write(data);
    udp.endPacket();
  }
}
#endif

// ######### SETUP #######################

void setup()
{
  wdt_enable (WDTO_1S);

  pinMode(directionPin, OUTPUT);
  digitalWrite(directionPin, LOW);

  Serial.begin(9600, SERIAL_8N1);

  DEBUG_PRINT(1, "\nApp started\n");
}

// ######### MAIN LOOP #######################

void loop()
{
  wdt_reset ();

  if (!eth_initialized) {
    initEthernet();
  }

  switch (state) {
    case STATE_WAIT_START:
      if (Serial.available()) {
        byte b = Serial.read();

        DEBUG_PRINTDATA(3, "%02x\n", b);

        if (b == START_CHAR) {
          DEBUG_PRINT(1, "Frame start found\n");
          buffer[0] = START_CHAR;
          index = 1;
          state = STATE_WAIT_DATA;
        }
      }
      break;

    case STATE_WAIT_DATA:
      if (Serial.available()) {
        byte b = Serial.read();
        DEBUG_PRINTDATA(3, "%02x\n", b);

        if (index >= MAX_DATA_LEN) {
          DEBUG_PRINT(1, "Too long message received\n");
          state = STATE_WAIT_START;
        } else {
          buffer[index++] = b;

          int msglen = checkNibeMessage(buffer, index);
          DEBUG_PRINTDATA(1, "checkNibeMessage=%d\n", msglen);

          switch (msglen) {
            case 0:   break;                            // Ok, but not ready
            case -1:  state = STATE_WAIT_START; break;  // Invalid message
            case -2:  state = STATE_CRC_FAILURE; break; // Checksum error
            default:  state = STATE_OK_MESSAGE_RECEIVED; break;
          }
        }
      }
      break;

    case STATE_CRC_FAILURE:
      DEBUG_PRINT(1, "CRC failure\n");
      
      // switch by address field
      switch (buffer[2]) {

        case ADDR_SMS40:
          if (ack_SMS40) {
            sendNakToNibe();
          }
          break;

        case ADDR_RMU40:
          if (ack_RMU40) {
            sendNakToNibe();
          }
          break;

        case ADDR_MODBUS40:
          if (ack_MODBUS40) {
            sendNakToNibe();
          }
          break;

        default:
          DEBUG_PRINTDATA(1, "Unknown address '%02x'\n", buffer[2]);
      }
      state = STATE_WAIT_START;
      break;

    case STATE_OK_MESSAGE_RECEIVED:
      DEBUG_PRINT(1, "Message received\n");
      state = STATE_WAIT_START;

      // switch by address field
      switch (buffer[2]) {

        case ADDR_SMS40:
          if (enable_SMS40) {
            if (ack_SMS40) {
              sendAckToNibe();
            }
            sendUdpPacket(buffer, index);
          }
          break;

        case ADDR_RMU40:
          if (enable_RMU40) {
            if (ack_RMU40) {
              sendAckToNibe();
            }
            sendUdpPacket(buffer, index);
          }
          break;

        case ADDR_MODBUS40:
          if (enable_MODBUS40) {
            if (buffer[3] == 0x69 && buffer[4] == 0x00) {
              state = STATE_TOKEN_RECEIVED;
            } else {
              if (ack_MODBUS40) {
                sendAckToNibe();
              }
              sendUdpPacket(buffer, index);
            }
          }
          break;

        default:
          DEBUG_PRINTDATA(1, "Unknown address '%02x'\n", buffer[2]);
      }
      break;

    case STATE_TOKEN_RECEIVED:
      int packetLen = udp.parsePacket();
      if (packetLen) {
        byte packet[packetLen];
        udp.read(packet, packetLen);
        sendDataToNibe(packet, packetLen);
      } else {
        if (ack_MODBUS40) {
          sendAckToNibe();
        }
      }
      state = STATE_WAIT_START;
      break;
  }
}


// ######### FUNCTIONS #######################

/*
 * Return:
 *  >0 if valid message received (return message length)
 *   0 if OK but message not ready
 *  -1 if invalid message
 *  -2 if checksum fails
 */
inline int checkNibeMessage(const byte* const data, const byte len)
{
  if (len >= 1) {
    if (data[0] != 0x5C) {
      return -1;
    }

    if (len >= 2) {
      if (!(data[1] == 0x00)) {
        return -1;
      }
    }

    if (len >= 6) {
      int datalen = data[4];

      if (len < datalen + 6) {
        return 0;
      }

      // calculate XOR checksum
      byte calc_checksum = 0;
      for (int i = 2; i < (datalen + 5); i++) {
        calc_checksum ^= data[i];
      }

      byte msg_checksum = data[datalen + 5];
      DEBUG_PRINTDATA(1, "Calculated checksum=%02x\n", calc_checksum);
      DEBUG_PRINTDATA(1, "Message checksum=%02x\n", msg_checksum);

      if (calc_checksum != msg_checksum) {

        // check special case, if checksum is 0x5C (start character),
        // heat pump seems to send 0xC5 checksum
        if (calc_checksum != 0x5C && msg_checksum != 0xC5) {
          return -2;
        }
      }

      return datalen + 6;
    }
  }

  return 0;
}

inline void sendAckToNibe()
{
  DEBUG_PRINT(1, "Send ACK\n");
  static byte data[1] = { 0x06 };
  sendDataToNibe(data, 1);
}

inline void sendNakToNibe()
{
  DEBUG_PRINT(1, "Send NAK\n");
  static byte data[1] = { 0x15 };
  sendDataToNibe(data, 1);
}

inline void sendDataToNibe(const byte * const data, const int len)
{
  DEBUG_PRINTDATA(2, "Sending data to serial port, len=%d\n", len);
  DEBUG_PRINTARRAY(2, data, len)
  DEBUG_PRINT(2, "\n");

  digitalWrite(directionPin, HIGH);
  delay(1);
  Serial.write(data, len);
  Serial.flush();
  delay(1);
  digitalWrite(directionPin, LOW);
}

inline void sendUdpPacket(const byte * const data, const int len)
{
  DEBUG_PRINTDATA(2, "Sending UDP packet, len=%d\n", len);
  DEBUG_PRINTARRAY(2, data, len)
  DEBUG_PRINT(2, "\n");

  udp.beginPacket(target_ip, target_port);
  udp.write(data, len);
  udp.endPacket();
}

inline void initEthernet()
{
  long sec = millis() / 1000;

  if (sec >= init_eth_timer) {
    Ethernet.begin(mac, ip);
    udp.begin(local_udp_port);
    eth_initialized = true;
  }
}

