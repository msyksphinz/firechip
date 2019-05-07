
#include <assert.h>
#include <stdio.h>

#include "matrixmul.h"
#include "util.h"
#include "dataset_small.h"

void show_result (int32_t *A, int64_t max_n, int64_t max_k)
{
  for (int j = 0; j < max_n; j++) {
    for (int i = 0; i < max_k; i++) {
      printf ("%d, ", A[j*max_k+i]);
    }
    printf ("\n");
  }
  return;
}


uint64_t matrixmul_sw (int32_t *output_data, int32_t *A, int32_t *B, int64_t max_n, int64_t max_m, int64_t max_k, int debug)
{
  volatile uint64_t start_cycle, stop_cycle;

  if (debug) { printf ("Software Start\n"); }
  start_cycle = read_csr(mcycle);
  for (int n = 0; n < max_n; n++) {
    for (int k = 0; k < max_k; k++) {
      output_data[n*max_k+k] = 0;
      for (int m = 0; m < max_m; m++) {
        output_data[n*max_k+k] += (A[n*max_m+m] * B[m*max_k+k]);
      }
    }
  }
  stop_cycle = read_csr(mcycle);
  if (debug) {
    printf ("Software Finished. %ld-%ld=%ld\n", stop_cycle, start_cycle, stop_cycle-start_cycle);
    show_result(output_data, max_n, max_k);
  }
  return stop_cycle - start_cycle;
}


uint64_t matrixmul_hw2 (int32_t *output_data, int32_t *A, int32_t *B, int64_t max_n, int64_t max_m, int64_t max_k, int debug)
{
  volatile uint64_t start_cycle, stop_cycle;

  if (debug) { printf ("Hardware Start <MatrixMul TwoRequester>\n"); }
  start_cycle = read_csr(mcycle);
  matrixmul_setM (max_m);
  matrixmul_setK (max_k);
  for (int i = 0; i < max_k; i++) {
    for (int j = 0; j < max_n; j++) {
      int64_t out_tmp;
      matrixmul (out_tmp, &(input1_data[j*max_m]), &(input2_data[i]));
      output_data[j*max_k+i+0] = out_tmp & 0x0ffffffffUL;
    }
  }
  stop_cycle = read_csr(mcycle);
  if (debug) {
    printf ("Hardware Finished. %ld-%ld=%ld\n", stop_cycle, start_cycle, stop_cycle-start_cycle);
    show_result(output_data, max_n, max_k);
  }
  return stop_cycle - start_cycle;
}

int main()
{

  uint64_t cycle_hw, cycle_sw;
  int32_t output_data[N*K]; // Allocate Largest Size

  printf ("                    SW          HW\n");
  printf ("----------------------------------\n");
  for (int size = 2; size <=16; size *= 2) {

    cycle_sw = matrixmul_sw (output_data, input1_data, input2_data, size, size, size, 0);
    cycle_hw = matrixmul_hw2 (output_data, input1_data, input2_data, size, size, size, 0);

    printf ("%2d x%2d x%2d : ", size, size, size);
    printf ("%10ld %10ld\n", cycle_sw, cycle_hw);
  }

  cycle_sw = matrixmul_sw  (output_data, input1_data, input2_data, N, M, K, 0);
  cycle_hw = matrixmul_hw2 (output_data, input1_data, input2_data, N, M, K, 0);

  printf ("18 x24 x28 : ");
  printf ("%10ld %10ld\n", cycle_sw, cycle_hw);

  return 0;
}
