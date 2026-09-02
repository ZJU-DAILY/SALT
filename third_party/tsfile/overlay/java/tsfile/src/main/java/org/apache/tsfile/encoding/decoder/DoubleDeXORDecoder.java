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

package org.apache.tsfile.encoding.decoder;

import org.apache.tsfile.encoding.DeXORTools;
import org.apache.tsfile.file.metadata.enums.TSEncoding;

import java.nio.ByteBuffer;

/**
 * This class includes code modified from Michael Burman's gorilla-tsc project.
 *
 * <p>Copyright: 2016-2018 Michael Burman and/or other contributors
 *
 * <p>Project page: https://github.com/burmanm/gorilla-tsc
 *
 * <p>License: http://www.apache.org/licenses/LICENSE-2.0
 */
public class DoubleDeXORDecoder extends GorillaDecoderV2 {
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

  public DoubleDeXORDecoder() {
    this.previous_value = 0;
    this.previous_q = 0;
    this.previous_delta = 0;
    this.previous_exp = 1023;
    this.EL = 1;
    this.contract_step = 0;
    this.previous_alpha = 0;
    this.setType(TSEncoding.DEXOR);
  }

  @Override
  public void reset() {
    super.reset();
    this.previous_value = 0;
    this.previous_q = 0;
    this.previous_delta = 0;
    this.previous_exp = 1023;
    this.EL = 1;
    this.contract_step = 0;
    this.previous_alpha = 0;
  }

  protected double ExceptionDecode(ByteBuffer in) {
    int bias = DeXORTools.getP2(EL - 1) - 1;
    long delta = readLong(EL, in) - bias;
    long lv;
    if (delta >= -bias && delta <= bias) {
      previous_exp += delta;
      lv = readBit(in) ? 1 : 0;
      lv = (lv << 11) | previous_exp;
      long seg = readLong(52, in);
      lv = (lv << 52) | seg;

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
      lv = readLong(64, in);
      previous_exp = DeXORTools.segment(lv, 2, 12);

      if (EL < 10) {
        EL++;
        contract_step = 0;
      }
    }
    return Double.longBitsToDouble(lv);
  }

  @Override
  public final double readDouble(ByteBuffer in) {
    double returnValue = previous_value;
    if (!firstValueWasRead) {
      flipByte(in);
      firstValueWasRead = true;
      returnValue = cacheNext(in);
    }
    cacheNext(in);
    return returnValue;
  }

  protected double cacheNext(ByteBuffer in) {
    readNext(in);
    if (Double.doubleToRawLongBits(previous_value) == DEXOR_ENCODING_ENDING) {
      hasNext = false;
    }
    return previous_value;
  }

  @SuppressWarnings("squid:S128")
  protected double readNext(ByteBuffer in) {
    int con = (int) readLong(2, in);
    if (con == 3) { // overflow Exception
      return ExceptionDecode(in);
    }

    if (con == 0 || con == 1) {
      if (con == 0) previous_q = (int) (readLong(5, in) - 20);
      previous_delta = (int) readLong(4, in);
      double pow = DeXORTools.getP10(previous_q + previous_delta);
      previous_alpha = DeXORTools.truncate(previous_value / pow) * pow;
    }

    long sign = previous_alpha > 0 ? 1 : -1;
    if (DeXORTools.comp(previous_alpha, 0) == 0) sign = readBit(in) ? 1 : -1; // sign
    long beta_star = sign * readLong(DeXORTools.decimalBits(previous_delta), in);
    double beta = beta_star * DeXORTools.getP10(previous_q);

    previous_value = previous_alpha + beta;

    return previous_value;
  }
}
