/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.tsfile.encoding.encoder;

import org.apache.tsfile.encoding.DeXORTools;
import org.apache.tsfile.file.metadata.enums.TSEncoding;

import java.io.ByteArrayOutputStream;

import static org.apache.tsfile.common.conf.TSFileConfig.LEADING_ZERO_BITS_LENGTH_64BIT;
import static org.apache.tsfile.common.conf.TSFileConfig.MEANINGFUL_XOR_BITS_LENGTH_64BIT;
import static org.apache.tsfile.common.conf.TSFileConfig.VALUE_BITS_LENGTH_64BIT;

public class DoubleDeXOREncoder extends GorillaEncoderV2 {
  private static final Double DEXOR_ENCODING_ENDING = 998274353.66;
  protected int size = 64;
  protected double previous_value = 0;
  protected int previous_q = 0;
  protected int previous_delta = 0;

  protected long previous_exp = 1023;
  protected int EL = 1;
  protected int contract_step = 0;

  protected double previous_alpha = 0;

  protected int rho = 8;

  public DoubleDeXOREncoder() {
    this.previous_value = 0;
    this.previous_q = 0;
    this.previous_delta = 0;
    this.previous_exp = 1023;
    this.EL = 1;
    this.contract_step = 0;
    this.previous_alpha = 0;
    this.setType(TSEncoding.DEXOR);
  }

  private static final int ONE_ITEM_MAX_SIZE =
      (2
                  + LEADING_ZERO_BITS_LENGTH_64BIT
                  + MEANINGFUL_XOR_BITS_LENGTH_64BIT
                  + VALUE_BITS_LENGTH_64BIT)
              / Byte.SIZE
          + 1;

  @Override
  public final int getOneItemMaxSize() {
    return ONE_ITEM_MAX_SIZE;
  }

  @Override
  public final void encode(double value, ByteArrayOutputStream out) {
    Decimal_XOR(value, out);
  }

  @Override
  public void flush(ByteArrayOutputStream out) {
    // ending stream
    encode(DEXOR_ENCODING_ENDING, out);

    // flip the byte no matter it is empty or not
    // the empty ending byte is necessary when decoding
    bitsLeft = 0;
    flipByte(out);

    // the encoder may be reused, so let us reset it
    reset();
  }

  @Override
  protected void reset() {
    super.reset();
    this.previous_value = 0;
    this.previous_q = 0;
    this.previous_delta = 0;
    this.previous_exp = 1023;
    this.EL = 1;
    this.contract_step = 0;
    this.previous_alpha = 0;
  }

  protected void ExceptionHandle(double value, ByteArrayOutputStream out) {
    long lv = Double.doubleToRawLongBits(value);
    long exp = DeXORTools.segment(lv, 2, 12);
    long delta = exp - previous_exp;
    int bias = DeXORTools.getP2(EL - 1) - 1;
    if (delta >= -bias && delta <= bias) {

      writeBits(delta + bias, EL, out);

      if (lv < 0) writeBit(out);
      else skipBit(out);

      writeBits(lv, 52, out);

      if (EL > 1) {
        int su_bias = DeXORTools.getP2(EL - 2) - 1;
        if (delta >= -su_bias && delta <= su_bias) {
          contract_step++;
        } else {
          contract_step = 0;
        }
        if (contract_step == rho) {
          EL--;
          contract_step = 0;
        }
      }
    } else {
      writeBits(DeXORTools.getP2(EL) - 1, EL, out);
      writeBits(lv, 64, out);
      contract_step = 0;

      if (EL < 10) {
        EL++;
      }
    }
    previous_exp = exp;
  }

  protected void Decimal_XOR(double value, ByteArrayOutputStream out) {
    int q = DeXORTools.getEnd(value, previous_q);

    int delta = 0;
    double alpha = 0;
    while (delta < 16) {
      double pow = DeXORTools.getP10(q + delta);
      long a = DeXORTools.truncate(value / pow);
      long b = DeXORTools.truncate(previous_value / pow);
      if (a == b) {
        alpha = a * pow;
        break;
      }
      delta++;
    }
    double pow = DeXORTools.getP10(q);
    double residual = value - alpha;
    long beta = Math.round((residual) / pow);

    if (delta >= 16 || DeXORTools.comp(alpha + beta * pow, value, pow) != 0) { // Exception 10
      writeBit(out);
      writeBit(out);
      ExceptionHandle(value, out);
      return;
    }

    beta = Math.abs(beta);
    boolean flag = q == previous_q;
    if (flag && delta == previous_delta) { //
      // same method 10
      writeBit(out);
      skipBit(out);
    } else {
      skipBit(out); // !flag || dp != pre_dp
      if (flag) writeBit(out);
      else skipBit(out);
      if (!flag) { // 00
        writeBits(q + 20, 5, out);
        previous_q = q;
      }
      writeBits(delta, 4, out);
      previous_delta = delta;
    }

    // extra info
    if (DeXORTools.comp(alpha, 0) == 0) {
      if (value > 0) writeBit(out);
      else skipBit(out);
    }

    writeBits(beta, DeXORTools.decimalBits(delta), out);
    previous_value = value;
  }
}
