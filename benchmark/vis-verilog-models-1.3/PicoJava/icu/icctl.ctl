# Properties derived from ibuf_monitor.v.
#
# Allowed patterns for valid, iu_shift_d, ic_fill_sel:

# v0r0w0:0000000000000000  (0)  00000001  (0)	   1111111111111111  (0)
# v1r0w1:0000000000000001  (1)  00000001  (0)      1111111111111110  (1)
# v1r1w0:0000000000000001  (1)  00000010  (1)      1111111111111111  (0)
# v2r0w2:0000000000000011  (2)  00000001  (0)      1111111111111100  (2)
# v2r1w1:0000000000000011  (2)  00000010  (1)      1111111111111110  (1)
# v2r2w0:0000000000000011  (2)  00000100  (2)      1111111111111111  (0)
# v3r0w3:0000000000000111  (3)  00000001  (0)      1111111111111000  (3)
# v3r1w2:0000000000000111  (3)  00000010  (1)      1111111111111100  (2)

# and so on.


\define iu_shift_one_hot ibuf_ctl.iu_shift_e[7:0]={1,2,4,8,16,32,64,128}

#FAIL:
AG(\iu_shift_one_hot -> !E(\iu_shift_one_hot U
   \iu_shift_one_hot *
   (valid[1]=1  * valid[0]=0  +
    valid[2]=1  * valid[1]=0  +
    valid[3]=1  * valid[2]=0  +
    valid[4]=1  * valid[3]=0  +
    valid[5]=1  * valid[4]=0  +
    valid[6]=1  * valid[5]=0  +
    valid[7]=1  * valid[6]=0  +
    valid[8]=1  * valid[7]=0  +
    valid[9]=1  * valid[8]=0  +
    valid[10]=1 * valid[9]=0  +
    valid[11]=1 * valid[10]=0 +
    valid[12]=1 * valid[11]=0 +
    valid[13]=1 * valid[12]=0 +
    valid[14]=1 * valid[13]=0 +
    valid[15]=1 * valid[14]=0 +
    ibuf_ctl.ic_fill_sel[1]=0  * ibuf_ctl.ic_fill_sel[0]=1  +
    ibuf_ctl.ic_fill_sel[2]=0  * ibuf_ctl.ic_fill_sel[1]=1  +
    ibuf_ctl.ic_fill_sel[3]=0  * ibuf_ctl.ic_fill_sel[2]=1  +
    ibuf_ctl.ic_fill_sel[4]=0  * ibuf_ctl.ic_fill_sel[3]=1  +
    ibuf_ctl.ic_fill_sel[5]=0  * ibuf_ctl.ic_fill_sel[4]=1  +
    ibuf_ctl.ic_fill_sel[6]=0  * ibuf_ctl.ic_fill_sel[5]=1  +
    ibuf_ctl.ic_fill_sel[7]=0  * ibuf_ctl.ic_fill_sel[6]=1  +
    ibuf_ctl.ic_fill_sel[8]=0  * ibuf_ctl.ic_fill_sel[7]=1  +
    ibuf_ctl.ic_fill_sel[9]=0  * ibuf_ctl.ic_fill_sel[8]=1  +
    ibuf_ctl.ic_fill_sel[10]=0 * ibuf_ctl.ic_fill_sel[9]=1  +
    ibuf_ctl.ic_fill_sel[11]=0 * ibuf_ctl.ic_fill_sel[10]=1 +
    ibuf_ctl.ic_fill_sel[12]=0 * ibuf_ctl.ic_fill_sel[11]=1 +
    ibuf_ctl.ic_fill_sel[13]=0 * ibuf_ctl.ic_fill_sel[12]=1 +
    ibuf_ctl.ic_fill_sel[14]=0 * ibuf_ctl.ic_fill_sel[13]=1 +
    ibuf_ctl.ic_fill_sel[15]=0 * ibuf_ctl.ic_fill_sel[14]=1)));

#!E(\iu_shift_one_hot U \iu_shift_one_hot *
#   (valid[0]=0 * (ibuf_ctl.iu_shift_e[0]=0 + ibuf_ctl.ic_fill_sel[0]=0))
#  );

#!E(\iu_shift_one_hot U \iu_shift_one_hot *
#   (ibuf_ctl.iu_shift_e[7:0]=1 * !(ibuf_ctl.ic_fill_sel[15] == valid[15]) *
#   !(ibuf_ctl.ic_fill_sel[14] == valid[14]))
#  );
