/*******************************************************
Program  : MOTOR DRIVER H-BRIDGE MODULE IBT-2
Chip     : Arduino Uno
Sumber   : www.hessmer.org
Modified : www.Labelektronika.com
********************************************************/
const char EOPmarker = '.'; //This is the end of packet marker
char serialbuf[32]; //This gives the incoming serial some room. Change it if you want a longer incoming.

#include <SoftwareSerial.h>
#include <string.h> // we'll need this for subString
#define MAX_STRING_LEN 20 // like 3 lines above, change as needed.

SoftwareSerial SoftSer(11, 10); // RX, TX

#include <RobotMotors.h>

//MPU9250 mpu;
char* subStr (char* input_string, char *separator, int segment_number) {
  char *act, *sub, *ptr;
  static char copy[MAX_STRING_LEN];
  int i;
  strcpy(copy, input_string);
  for (i = 1, act = copy; i <= segment_number; i++, act = NULL) {
    sub = strtok_r(act, separator, &ptr);
    if (sub == NULL) break;
  }
 return sub;
}

#define POT_PIN  0 // Pin Potentiometer A0
#define FL1      5
#define FL2      6
#define FR1      10
#define FR2      11
#define BL1      3
#define BL2      9
#define BR1      12
#define BR2      13

int digSpd = 0;
int sv = 0;
RobotMotors M = RobotMotors(FL1, FL2, FR1, FR2, BL1, BL2, BR1, BR2);

void setup()
{
  Serial.begin(38400);
  SoftSer.begin(38400);
//pinMode(FL1, OUTPUT);
//pinMode(FL2, OUTPUT);
//pinMode(FR1, OUTPUT);
//pinMode(FR2, OUTPUT);
//pinMode(BL1, OUTPUT);
//pinMode(BL2, OUTPUT);
//pinMode(BR1, OUTPUT);
//pinMode(BR2, OUTPUT);

// Wire.begin();
/*    delay(2000);

    if (!mpu.setup(0x68)) {  // change to your own address
        while (1) {
            Serial.println("MPU connection failed. Please check your connection with `connection_check` example.");
            delay(5000);
        }
        }


    Serial.println("Accel Gyro calibration will start in 5sec.");
    Serial.println("Please leave the device still on the flat plane.");
    mpu.verbose(true);
    delay(5000);
    mpu.calibrateAccelGyro();

    Serial.println("Mag calibration will start in 5sec.");
    Serial.println("Please Wave device in a figure eight until done.");
    delay(5000);
    mpu.calibrateMag();

 
    mpu.verbose(false);
*/    
}
int mcheck(int mov, int dela){
  if(mov==0){
      fwds(M,125,dela);
    }
  if(mov==1){
      fwdr(M,125,dela);
    }
  if(mov==2){
      fwdl(M,125,dela);
    }  
  if(mov==3){
      left(M,125,dela);
    }
  if(mov==4){
      revl(M,125,dela);
    }
  if(mov==5){
      revs(M,125,dela);
    }
  if(mov==6){
      revr(M,125,dela);
    }
  if(mov==7){
      rght(M,125,dela);
    }  
  
}
void loop()
{
  // angle();

  if (Serial.available() > 0) {
    String data = Serial.readStringUntil('\n');
    Serial.println(data);
  }

   if (SoftSer.available() > 0) { //makes sure something is ready to be read
      static int bufpos = 0; //starts the buffer back at the first position in the incoming serial.read
      char inchar = SoftSer.read(); //assigns one byte (as serial.read()'s only input one byte at a time
      if (inchar != EOPmarker) { //if the incoming character is not the byte that is the incoming package ender
        serialbuf[bufpos] = inchar; //the buffer position in the array get assigned to the current read
        bufpos++; //once that has happend the buffer advances, doing this over and over again until the end of package marker is read.
      }
      else { //once the end of package marker has been read
        serialbuf[bufpos] = 0; //restart the buff
        bufpos = 0; //restart the position of the buff

        int m1 = subStr(serialbuf, ",", 1);
        int d1=  subStr(serialbuf, ",", 2);
       
        Serial.print("The first number, m1 is: ");
        Serial.print(m1);
        Serial.print(" - The second number, a1 is: ");
        Serial.print(d1);
        mcheck(m1,d1);
        Serial.println();
        
        
      }
    }
   /* 
// sensor value range 0 to 1023
delay(5000);
sv = analogRead(POT_PIN);
Serial.println(sv);
sv = 331;

fwds(M, sv);
delay(4000);

halt(M, sv);
delay(500);

rght(M, sv);
delay(3000);

halt(M, sv);
delay(500);

revs(M, sv);
delay(4000);

halt(M, sv);
delay(500);

left(M, sv);
delay(3000);

halt(M, sv);
delay(1000);

sv = 255;

rclw(M, sv);
delay(2000);

halt(M, sv);
delay(500);

rccw(M, sv);
delay(2000);

halt(M, sv);
delay(1000);

sv = 443;

fwdr(M, sv);
delay(3000);

halt(M, sv);
delay(500);

revr(M, sv);
delay(3000);

halt(M, sv);
delay(500);

revl(M, sv);
delay(3000);

halt(M, sv);
delay(500);

fwdl(M, sv);
delay(3000);
*/
}

/*void angle(){
  if (mpu.update()) {
        static uint32_t prev_ms = millis();
        if (millis() > prev_ms + 250) {
          Serial.print("\nYaw = ");
          Serial.print(mpu.getYaw(), 2);
        }
    }
  }*/

  
void fwds(RobotMotors mtr, int spd, int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, digSpd);
  analogWrite(mtr.fl2, 0);
  analogWrite(mtr.fr1, digSpd);
  analogWrite(mtr.fr2, 0);
  analogWrite(mtr.bl1, digSpd);
  analogWrite(mtr.bl2, 0);
  analogWrite(mtr.br1, digSpd);
  analogWrite(mtr.br2, 0);
}

void revs(RobotMotors mtr, int spd,int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, 0);
  analogWrite(mtr.fl2, digSpd);
  analogWrite(mtr.fr1, 0);
  analogWrite(mtr.fr2, digSpd);
  analogWrite(mtr.bl1, 0);
  analogWrite(mtr.bl2, digSpd);
  analogWrite(mtr.br1, 0);
  analogWrite(mtr.br2, digSpd);
}

void left(RobotMotors mtr, int spd,int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, 0);
  analogWrite(mtr.fl2, digSpd);
  analogWrite(mtr.fr1, digSpd);
  analogWrite(mtr.fr2, 0);
  analogWrite(mtr.bl1, digSpd);
  analogWrite(mtr.bl2, 0);
  analogWrite(mtr.br1, 0);
  analogWrite(mtr.br2, digSpd);
}

void rght(RobotMotors mtr, int spd, int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, digSpd);
  analogWrite(mtr.fl2, 0);
  analogWrite(mtr.fr1, 0);
  analogWrite(mtr.fr2, digSpd);
  analogWrite(mtr.bl1, 0);
  analogWrite(mtr.bl2, digSpd);
  analogWrite(mtr.br1, digSpd);
  analogWrite(mtr.br2, 0);
}

void rclw(RobotMotors mtr, int spd,int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, digSpd);
  analogWrite(mtr.fl2, 0);
  analogWrite(mtr.fr1, 0);
  analogWrite(mtr.fr2, digSpd);
  analogWrite(mtr.bl1, digSpd);
  analogWrite(mtr.bl2, 0);
  analogWrite(mtr.br1, 0);
  analogWrite(mtr.br2, digSpd);
}

void rccw(RobotMotors mtr, int spd, int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, 0);
  analogWrite(mtr.fl2, digSpd);
  analogWrite(mtr.fr1, digSpd);
  analogWrite(mtr.fr2, 0);
  analogWrite(mtr.bl1, 0);
  analogWrite(mtr.bl2, digSpd);
  analogWrite(mtr.br1, digSpd);
  analogWrite(mtr.br2, 0);
}

void fwdl(RobotMotors mtr, int spd, int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, 0);
  analogWrite(mtr.fl2, 0);
  analogWrite(mtr.fr1, digSpd);
  analogWrite(mtr.fr2, 0);
  analogWrite(mtr.bl1, digSpd);
  analogWrite(mtr.bl2, 0);
  analogWrite(mtr.br1, 0);
  analogWrite(mtr.br2, 0);
}

void fwdr(RobotMotors mtr, int spd, int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, digSpd);
  analogWrite(mtr.fl2, 0);
  analogWrite(mtr.fr1, 0);
  analogWrite(mtr.fr2, 0);
  analogWrite(mtr.bl1, 0);
  analogWrite(mtr.bl2, 0);
  analogWrite(mtr.br1, digSpd);
  analogWrite(mtr.br2, 0);
}

void revl(RobotMotors mtr, int spd, int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, 0);
  analogWrite(mtr.fl2, digSpd);
  analogWrite(mtr.fr1, 0);
  analogWrite(mtr.fr2, 0);
  analogWrite(mtr.bl1, 0);
  analogWrite(mtr.bl2, 0);
  analogWrite(mtr.br1, 0);
  analogWrite(mtr.br2, digSpd);
}

void revr(RobotMotors mtr, int spd, int del) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, 0);
  analogWrite(mtr.fl2, 0);
  analogWrite(mtr.fr1, 0);
  analogWrite(mtr.fr2, digSpd);
  analogWrite(mtr.bl1, 0);
  analogWrite(mtr.bl2, digSpd);
  analogWrite(mtr.br1, 0);
  analogWrite(mtr.br2, 0);
}

void halt(RobotMotors mtr, int spd) {
  digSpd = (spd + 1)/4 - 1;
  analogWrite(mtr.fl1, 0);
  analogWrite(mtr.fl2, 0);
  analogWrite(mtr.fr1, 0);
  analogWrite(mtr.fr2, 0);
  analogWrite(mtr.bl1, 0);
  analogWrite(mtr.bl2, 0);
  analogWrite(mtr.br1, 0);
  analogWrite(mtr.br2, 0);
}
