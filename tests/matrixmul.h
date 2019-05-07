#ifndef MATRIXMUL_H
#define MATRIXMUL_H

#include "rocc.h"

#define XCUSTOM_MATRIXMUL 3

#define k_MTRXMUL_SETM   (0)
#define k_MTRXMUL_SETK   (1)
#define k_MTRXMUL_DOCALC (2)

#define matrixmul_setM(len)                                    \
  ROCC_INSTRUCTION_S(XCUSTOM_MATRIXMUL, len, k_MTRXMUL_SETM);
#define matrixmul_setK(len)                                    \
  ROCC_INSTRUCTION_S(XCUSTOM_MATRIXMUL, len, k_MTRXMUL_SETK);
#define matrixmul(y, mem_addr0, mem_addr1)                            \
  ROCC_INSTRUCTION_DSS(XCUSTOM_MATRIXMUL, y, mem_addr0, mem_addr1, k_MTRXMUL_DOCALC);

#endif  // MATRIXMUL_H
