PTDbg : Post {
	classvar <>debug = false;
	classvar <>slow = false;
	classvar <>complexity = 0;
	classvar <>f = nil;

	* manageFile {
		if(PTDbg.debug, {
			if (PTDbg.f == nil, {
				if (PathName.new("~/data/phonotype").isFolder, {
					PTDbg.f = File.open(PathName.new("~/dust/data/phonotype/debug.txt").fullPath, "w");
				}, {
					PTDbg.f = File.open(PathName.new("~/debug.txt").fullPath, "w");
				});
			});
		}, {
			if (PTDbg.f != nil, {
				f.close;
				f = nil;
			});
		});
	}

	* complex {
		complexity = complexity + 1;
		if (slow && (complexity > 6000), {
			Error.new("Too complex").throw;
		});
	}

	* put { arg item;
		PTDbg.manageFile;
		if (PTDbg.debug, {
			PTDbg.f.put(item);
			PTDbg.f.flush;
			item.post;
		});
	}
	* putAll { arg aCollection;
		PTDbg.manageFile;
		if (PTDbg.debug, {
			PTDbg.f.putAll(aCollection);
			PTDbg.f.flush;
			aCollection.post;
		});
	}
}

PTCheckError : Error {
	errorString {
		^what
	}
}

PTParseError : Error {
	errorString {
		^what
	}
}

PTEditError : Error {
	errorString {
		^what
	}
}

PTOp {
	var <name, <nargs;

	*new { |name, nargs|
		^super.newCopyArgs(name, nargs);
	}

	stringWithArgsResources { |args, resources|
		^this.asString;
	}

	check { |args| }

	min { |args, resources|
		^-1;
	}

	max { |args, resources|
		^1;
	}

	rate { |args, resources|
		var ret = \control;
		args.do({ |x|
			if (x.rate == \audio, {ret = \audio});
		});
		^ret;
	}

	alloc { |args, callSite|
		^nil;
	}

	// commit runs as part of a Routine; it can yield.
	commit { |args, resources|
		args.do { |a|
			a.commit;
		}
	}

	usesIt { |args|
		^args.any { |x| x.usesIt };
	}


	*instantiateAll { |args, resources|
		^args.collect({|x| x.instantiate()});
	}

	instantiateHelper { |c, rate, iargs|
		^if (rate == \audio, {c.ar(*iargs)}, {c.kr(*iargs)});
	}

	i { |c, args|
		var iargs = PTOp.instantiateAll(args);
		^this.instantiateHelper(c, this.rate(args), iargs);
	}

}

PTNode {
	var <op, <args, <resources;
	*new { |op, args, callSite=nil|
		try {
			if (args.size != op.nargs, {
				Error.new(op.name ++ " Args size " ++ args ++ " does not match number of args " ++ op.nargs).throw;
			});
			op.check(args);
		} { |e|
			args.do { |a|
				a.free;
			};
			e.throw;
		};
		^super.newCopyArgs(op, args, op.alloc(args, callSite));
	}

	min {
		^op.min(args, resources);
	}

	max {
		^op.max(args, resources);
	}

	commit {
		op.commit(args, resources);
	}

	isConstant {
		^(this.min == this.max);
	}

	rate {
		^op.rate(args, resources);
	}

	usesIt {
		^op.usesIt(args);
	}

	instantiate {
		^op.instantiate(args, resources);
	}

	free {
		PTDbg << "Freeing " << this.op << "\n";
		if (resources != nil, {
			resources.do { |x|
				PTDbg << "Freeing resources " << x << "\n";
				x.free();
			};
		});
		args.do { |x| x.free };
	}

	printOn { | stream |
		stream << "PTNode( " << op.stringWithArgsResources(args, resources) << ", " << this.args << " )";
    }
}


PTLiteral : PTOp {

	var n;

	*new{ |n|
		^super.newCopyArgs("", 0, n);
	}

	min { |args, resources|
		^n;
	}

	max { |args, resources|
		^n;
	}

	rate { |args, resources|
		^\control;
	}

	commit {}

	instantiate { |args, resources|
		^n;
	}
}

PTConst : PTOp {

	var value;

	*new { |name, value|
		^super.newCopyArgs(name, 0, value);
	}

	min { |args, resources|
		^value;
	}

	max { |args, resources|
		^value;
	}

	rate { |args, resources|
		^\control;
	}

	instantiate { |args, resources|
		^value;
	}
}

PTOscOp : PTOp {
	var delegate, delegateLF;

	*new{ |name, nargs, delegate, delegateLF|
		^super.newCopyArgs(name, nargs, delegate, delegateLF);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		var f = iargs[0];
		^switch(this.rate(args),
			\audio, {delegate.ar(freq: f)},
			\control, {delegateLF.kr(freq: f)},
			{delegate.ar(freq: f)},
		);
	}

	rate { |args, resources|
		// Assume the first arg is frequency.
		^if(args[0].max > 10, { \audio }, { \control });
	}
}

PTNoiseOp : PTOp {
	var delegate;
	*new { |name, delegate|
		^super.newCopyArgs(name, 0, delegate);
	}

	rate {
		^\audio;
	}

	instantiate {
		PTDbg << "Instantiating the " << delegate << "\n";
		^delegate.ar;
	}
}

PTEnvOp : PTOp {

	var envFunc;

	* new { |name, nargs, envFunc|
		^super.newCopyArgs(name, nargs, envFunc);
	}

	check { |args|
		args[1..].do { |a|
			if (a.isConstant.not, {
				PTCheckError.new("Envelopes have constant parameters")
			});
		};
	}

	min {^0}
	max {^1}
	rate {^\control}

	instantiate { |args, resources|
		var signal = args[0];
		var parameters = args[1..].collect({|x| x.min});
		^EnvGen.kr(envFunc.value(*parameters), signal.instantiate);
	}
}

PTScaledEnvOp : PTOp {

	var envFunc;

	* new { |name, nargs, envFunc|
		^super.newCopyArgs(name, nargs, envFunc);
	}

	check { |args|
		args[2..].do { |a|
			if (a.isConstant.not, {
				PTCheckError.new("Envelopes have constant parameters").throw;
			});
		};
	}

	min {^0}
	max {^1}
	rate {^\control}

	instantiate { |args, resources|
		var signal = args[0];
		var timeScale = args[1];
		var parameters = args[2..].collect({|x| x.min});
		^EnvGen.kr(envFunc.value(*parameters), signal.instantiate, timeScale: timeScale.instantiate);
	}
}

PTAREnvOp : PTScaledEnvOp {
	check { |args|
		super.check(args);
		if ((args[2].min < 0) || (args[2].min > 1), {
			PTCheckError.new("Attack must be between 0 and 1").throw;
		});
	}
}

PTOscOpWidth : PTOscOp {

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		var f = iargs[0];
		var width = iargs[1];
		^switch(this.rate(args),
			\audio, {delegate.ar(freq: f, width: width)},
			\control, {delegateLF.kr(freq: f, width: width)},
			{delegate.ar(freq: f, width: width)},
		);
	}
}

PTOscOpPhase : PTOscOp {

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		var f = iargs[0];
		var phase = iargs[1];
		^switch(this.rate(args),
			\audio, {delegate.ar(freq: f, phase: phase)},
			\control, {delegateLF.kr(freq: f, phase: phase)},
			{delegate.ar(freq: f, phase: phase)},
		);
	}
}


// An audio-rate zero
PTSilenceOp : PTConst {
	*new {
		^super.newCopyArgs("SILENCE", 0, 0);
	}

	rate { |args, resources|
		^\audio;
	}
}

PTDelegatedOp : PTOp {
	var c;

	*new { |name, nargs, c|
		^super.newCopyArgs(name, nargs, c);
	}

	instantiate { |args, resources|
		^this.i(c, args);
	}

	min { |args|
		^min(*args.collect({|x| x.min}));
	}

	max { |args|
		^max(*args.collect({|x| x.max}));
	}

}

PT01DelegatedOp : PTOp {
	var c;

	*new { |name, nargs, c|
		^super.newCopyArgs(name, nargs, c);
	}

	instantiate { |args, resources|
		^this.i(c, args);
	}

	min { |args|
		^0;
	}

	max { |args|
		^1;
	}

}

PTPosOp : PTOp {
	*new {
		^super.newCopyArgs("POS", 1);
	}

	min { |args, resources|
		^0;
	}

	max { |args, resources|
		^args[0].max;
	}

	instantiate { |args, resources|
		^args[0].instantiate.clip(0, inf);
	}
}

PTLpgOp : PTOp {
	*new {
		^super.newCopyArgs("LPG", 2);
	}

	min { |args, resources|
		^args[0].min;
	}

	max { |args, resources|
		^args[0].max;
	}

	rate {
		^\audio;
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args, resources);
		var laggy = iargs[1].clip(0, inf).lagud(0.015, 0.15);
		^(laggy * LPF.ar(iargs[0], laggy.linexp(0, 1, 20, 20000)));
	}
}

PTDJFOp : PTOp {
	*new {
		^super.newCopyArgs("DJF", 2);
	}

	min { |args, resources|
		^args[0].min;
	}

	max { |args, resources|
		^args[0].max;
	}

	rate {
		^\audio;
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args, resources);
		var hpf = iargs[1].clip(0, 1).linexp(0, 1, 20, 10000);
		var lpf = (1 + (iargs[1].clip(-1, 0))).linexp(0, 1, 40, 20000);
		^HPF.ar(LPF.ar(iargs[0], lpf), hpf);
	}
}

PTFilterOp : PTOp {
	var c;

	*new { |name, nargs, c|
		^super.newCopyArgs(name, nargs, c);
	}

	min { |args, resources|
		^args[0].min;
	}

	max { |args, resources|
		^args[0].max;
	}

	instantiate { |args, resources|
		^this.i(c, args);
	}
}

PTWrapOp : PTFilterOp {
	*new {
		^super.newCopyArgs("WRAP", 3, Wrap);
	}

	min { |args, resources|
		^args[1].min;
	}

	max { |args, resources|
		^args[2].max;
	}
}

PTClipOp : PTFilterOp {
	*new {
		^super.newCopyArgs("CLIP", 3, Clip);
	}

	min { |args, resources|
		^args[1].min;
	}

	max { |args, resources|
		^args[2].max;
	}
}

PTPlusOp : PTOp {
	*new { |name, nargs|
		^super.newCopyArgs(name, nargs);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^iargs[0] + iargs[1];
	}

	min { |args, resources|
		^args.sum {|i| i.min};
	}

	max { |args, resources|
		^args.sum {|i| i.max};
	}

}

PTMinOp : PTOp {
	*new {
		^super.newCopyArgs("MIN", 2);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^iargs[0] min: iargs[1];
	}

	min { |args, resources|
		^args[0].min min: args[1].min;
	}

	max { |args, resources|
		^args[0].max min: args[1].max;
	}

}

PTMaxOp : PTOp {
	*new { |name, nargs|
		^super.newCopyArgs("MAX", 2);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^iargs[0] max: iargs[1];
	}

	min { |args, resources|
		^args[0].min max: args[1].min;
	}

	max { |args, resources|
		^args[0].max max: args[1].max;
	}

}

PTGTOp : PTOp {
	*new {
		^super.newCopyArgs(">", 2);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^iargs[0] > iargs[1];
	}

	min { |args, resources|
		^0;
	}

	max { |args, resources|
		^1;
	}

}

PTLTOp : PTOp {
	*new {
		^super.newCopyArgs("<", 2);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^iargs[0] < iargs[1];
	}

	min { |args, resources|
		^0;
	}

	max { |args, resources|
		^1;
	}

}

PTMixOp : PTOp {

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^if (this.rate(args, resources) == \audio, {
			Mix.ar(iargs);
		}, {
			Mix.kr(iargs);
		});
	}

	min { |args, resources|
		^args.sum {|i| i.min};
	}

	max { |args, resources|
		^args.sum {|i| i.max};
	}
}

PTMinusOp : PTOp {
	*new { |name, nargs|
		^super.newCopyArgs(name, nargs);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^iargs[0] - iargs[1];
	}

	min { |args, resources|
		^args[0].min - args[1].max;
	}

	max { |args, resources|
		^args[0].max - args[1].min;
	}

}

PTTimesOp : PTOp {
	*new {
		^super.newCopyArgs("*", 2);
	}

	instantiate { |args, resources|
		var iargs;
		iargs = PTOp.instantiateAll(args);
		^iargs[0] * iargs[1];
	}

	min { |args, resources|
		^[
			args[0].min*args[1].min,
			args[0].min*args[1].max,
			args[0].max*args[1].min,
			args[0].max*args[1].max

		].minItem;
	}

	max { |args, resources|
		^[
			args[0].min*args[1].min,
			args[0].min*args[1].max,
			args[0].max*args[1].min,
			args[0].max*args[1].max

		].maxItem;
	}
}

PTDivOp : PTOp {
	*new {
		^super.newCopyArgs("/", 2);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^iargs[0] / iargs[1];
	}

	check { |args, resources|
		var denom = args[1];
		if ( (denom.min == 0) || (denom.max == 0) || (denom.min.sign != denom.max.sign), {
			PTCheckError.new("Denominator must exclude 0").throw;
		});
	}

	min { |args, resources|
		^[
			args[0].min/args[1].min,
			args[0].min/args[1].max,
			args[0].max/args[1].min,
			args[0].max/args[1].max

		].minItem;
	}

	max { |args, resources|
		^[
			args[0].min/args[1].min,
			args[0].min/args[1].max,
			args[0].max/args[1].min,
			args[0].max/args[1].max

		].maxItem;
	}
}

PTModOp : PTOp {
	*new {
		^super.newCopyArgs("%", 2);
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^iargs[0] % iargs[1];
	}

	min { |args, resources|
		^0;
	}

	max { |args, resources|
		^args[1].max;
	}
}

PTArgOp : PTOp {
	var symbol, r, minVal, maxVal;

	*new { |name, symbol, rate, minVal = -10, maxVal = 10|
		^super.newCopyArgs(name, 0, symbol, rate, minVal, maxVal);
	}

	rate { |args, resources|
		^r;
	}

	min { |args, resources|
		^minVal;
	}

	max { |args, resources|
		^maxVal;
	}

	usesIt {
		^(symbol == \in);
	}

	instantiate { |args, resources|
		^case
		{this.rate == \audio} {
			symbol.ar([0,0])
		}
		{this.rate == \control} {
			symbol.kr([0,0])
		}
		{true} {
			symbol.kr([0,0])
		}
	}
}

PTInOp : PTOp {

	*new {
		^super.newCopyArgs("IN", 0);
	}

	rate { |args, resources|
		^\audio;
	}

	min { |args, resources|
		^-1;
	}

	max { |args, resources|
		^1;
	}

	instantiate { |args, resources|
		^SoundIn.ar([0, 1]);
	}
}

PTLROp : PTOp {

	*new {
		^super.newCopyArgs("LR", 2);
	}

	check { |args|
		if (args[0].rate != args[1].rate, {
			PTCheckError.new("LR args must be same rate").throw;
		});
	}

	min { |args, resources|
		^if(args[0].min < args[1].min, {args[0].min}, {args[1].min});
	}

	max { |args, resources|
		^if(args[0].max < args[1].max, {args[0].max}, {args[1].max});
	}

	*mono { |ugen|
		^if (ugen.size == 0, {ugen}, {ugen.sum / ugen.size});
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args, resources);
		PTDbg << "IARGS " << iargs << "\n";
		^[PTLROp.mono(iargs[0]), PTLROp.mono(iargs[1])];
	}
}

PTMonoOp : PTOp {
	*new {
		^super.newCopyArgs("MONO", 1);
	}


	min { |args, resources|
		^args[0].min;
	}

	max { |args, resources|
		^args[0].max;
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args, resources);
		^PTLROp.mono(iargs[1]);
	}
}

PTRotateOp : PTOp {
	*new {
		^super.newCopyArgs("ROT", 2);
	}


	min { |args, resources|
		^args[0].min;
	}

	max { |args, resources|
		^args[0].max;
	}

	*mono { |ugen|
		^if (ugen.size == 0, {ugen}, {ugen.sum / ugen.size});
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args, resources);
		var stereo = PTScriptNet.maybeMakeStereo(iargs[0]);
		^this.instantiateHelper(Rotate2, this.rate(args), [stereo[0], stereo[1], PTLROp.mono(iargs[1])]);
	}
}

PTDelayOp : PTOp {

	*new {
		^super.newCopyArgs("DEL", 2);
	}

	check { |args|
		if (args[1].min < 0, {
			PTCheckError.new("DEL time should be positive").throw;
		});
		if (args[1].max > 10, {
			PTCheckError.new("DEL time should be < 10").throw;
		});
	}

	min { |args, resources|
		^args[0].min;
	}

	max { |args, resources|
		^args[0].max;
	}

	instantiate { |args, resources|
		^if (args[1].isConstant, {
			this.instantiateHelper(DelayN, this.rate(args), [args[0].instantiate, args[1].max, args[1].max]);
		}, {
			this.instantiateHelper(DelayL, this.rate(args), [args[0].instantiate, args[1].max, args[1].instantiate]);
		});
	}
}

PTAllPassOp : PTOp {

	var delegateN, delegateL;

	*new { |name, delegateN, delegateL|
		^super.newCopyArgs(name, 3, delegateN, delegateL);
	}

	check { |args|
		if (args[1].min < 0, {
			PTCheckError.new("DEL time should be positive").throw;
		});
		if (args[1].max > 10, {
			PTCheckError.new("DEL time should be < 10").throw;
		});
	}

	min { |args, resources|
		^args[0].min;
	}

	max { |args, resources|
		^args[0].max;
	}

	instantiate { |args, resources|
		^if (args[1].isConstant && args[2].isConstant, {
			this.instantiateHelper(delegateN, this.rate(args), [args[0].instantiate, args[1].max, args[1].max, args[2].max]);
		}, {
			this.instantiateHelper(delegateL, this.rate(args), [args[0].instantiate, args[1].max, args[1].instantiate, args[2].instantiate]);
		});
	}
}

PTSelectOp : PTOp {
	*new { |name, nargs|
		^super.newCopyArgs(name, nargs);
	}

	min { |args|
		^min(*args[1..].collect({|x| x.min}));
	}

	max { |args|
		^max(*args[1..].collect({|x| x.max}));
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		^if( this.rate == \audio, {
			Select.ar(iargs[0], iargs[1..]);
		}, {
			Select.kr(iargs[0], iargs[1..]);
		});
	}

}

PTSequenceOp : PTOp {
	*new { |name, nargs|
		^super.newCopyArgs(name, nargs);
	}

	min { |args|
		^min(*args[1..].collect({|x| x.min}));
	}

	max { |args|
		^max(*args[1..].collect({|x| x.max}));
	}

	instantiate { |args, resources|
		var iargs = PTOp.instantiateAll(args);
		var st = Stepper.kr(iargs[0], iargs[1], min: 0, max: nargs-3);
		^if( this.rate == \audio, {
			Select.ar(st, iargs[2..]);
		}, {
			Select.kr(st, iargs[2..]);
		});
	}

}


PTScaleOp : PTOp {
	*new {
		^super.newCopyArgs("SCL", 3);
	}

	check { |args|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		var newMin = args[1].min;
		var newMax = args[2].min;
		if (args[1].isConstant.not, {
			PTCheckError.new("Expected constant, got " ++ args[1].op.name).throw;
		});
		if (args[2].isConstant.not, {
			PTCheckError.new("Expected constant, got " ++ args[2].op.name).throw;
		});
		if (oldMin >= oldMax, {
			PTCheckError.new("Signal is constant or bad range data").throw;
		});
		if (newMin >= newMax, {
			PTCheckError.new("Min greater than max").throw;
		});
	}

	min { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;

		^args[1].min;
	}

	max { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		^args[2].max;
	}

	instantiate { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		var newMin = args[1].min;
		var newMax = args[2].min;

		^ args[0].instantiate.linlin(oldMin, oldMax, newMin, newMax, clip: nil);
	}
}

PTScaleExpOp : PTOp {
	*new {
		^super.newCopyArgs("SCL.X", 3);
	}

	check { |args|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		var newMin = args[1].min;
		var newMax = args[2].min;
		if (args[1].isConstant.not, {
			PTCheckError.new("Expected constant, got " ++ args[1].op.name).throw;
		});
		if (args[2].isConstant.not, {
			PTCheckError.new("Expected constant, got " ++ args[2].op.name).throw;
		});
		if (oldMin >= oldMax, {
			PTCheckError.new("Signal is constant or bad range data").throw;
		});
		if (newMin >= newMax, {
			PTCheckError.new("Min greater than max").throw;
		});
		if ((newMin <= 0) || (newMax <= 0), {
			PTCheckError.new("Output must be greater than zero");
		});
	}

	min { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;

		^args[1].min;
	}

	max { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		^args[2].max;
	}

	instantiate { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		var newMin = args[1].min;
		var newMax = args[2].min;

		^ args[0].instantiate.linexp(oldMin, oldMax, newMin, newMax, clip: 'minmax');
	}
}

PTUniOp : PTOp {
	*new {
		^super.newCopyArgs("UNI", 1);
	}

	check { |args|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		if (oldMin >= oldMax, {
			PTCheckError.new("Signal is constant or bad range data").throw;
		});
	}

	min { |args, resources|
		^0;
	}

	max { |args, resources|
		^1;
	}

	instantiate { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		^args[0].instantiate.linlin(oldMin, oldMax, 0, 1);
	}
}

PTSclVOp : PTOp {
	*new {
		^super.newCopyArgs("SCL.V", 1);
	}

	check { |args|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		if (oldMin >= oldMax, {
			PTCheckError.new("Signal is constant or bad range data").throw;
		});
	}

	min { |args, resources|
		^0;
	}

	max { |args, resources|
		^1;
	}

	instantiate { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		^args[0].instantiate.lincurve(oldMin, oldMax, 0, 1, curve: 6);
	}
}

PTBiOp : PTOp {
	*new {
		^super.newCopyArgs("BI", 1);
	}

	min { |args, resources|
		^-1;
	}

	max { |args, resources|
		^1;
	}

	instantiate { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		^args[0].instantiate.linlin(oldMin, oldMax, -1, 1, clip: nil);
	}
}

PTSclFOp : PTOp {
	*new {
		^super.newCopyArgs("SCL.F", 1);
	}

	min { |args, resources|
		^20;
	}

	max { |args, resources|
		^20000;
	}

	instantiate { |args, resources|
		var oldMin = args[0].min;
		var oldMax = args[0].max;
		^args[0].instantiate.linexp(oldMin, oldMax, 20, 20000, clip: 'minmax');
	}
}

PTFloorOp : PTOp {
	*new {
		^super.newCopyArgs("FLOOR", 1);
	}

	min { |args, resources|
		^args[0].min.floor;
	}

	max { |args, resources|
		^args[0].max.floor;
	}

	instantiate { |args, resources|
		^args[0].instantiate.floor;
	}
}

PTTanhOp : PTOp {
	*new {
		^super.newCopyArgs("TANH", 1);
	}

	instantiate { |args, resources|
		^args[0].instantiate.tanh;
	}
}

PTAbsOp : PTOp {
	*new {
		^super.newCopyArgs("ABS", 1);
	}

	max { |args|
		^max(args[0].max, -1 * args[0].min);
	}

	min {
		^0;
	}

	instantiate { |args, resources|
		^args[0].instantiate.abs;
	}
}

PTInvOp : PTOp {
	*new {
		^super.newCopyArgs("INV", 1);
	}

	max { |args|
		^(-1 * args[0].min);
	}

	min { |args|
		^(-1 * args[0].max);
	}

	instantiate { |args, resources|
		^(-1 * args[0].instantiate);
	}
}

PTFoldOp : PTOp {

	*new {
		^super.newCopyArgs("FOLD", 2);
	}

	*foldval { |x| ^(0.2/(0.2 + x.abs)) }

	min { |args|
		^args[0].min;
	}

	max { |args|
		^args[0].max;
	}

	instantiate { |args, resources|
		^args[0].instantiate.fold2(PTFoldOp.foldval(args[1].instantiate));
	}
}

PTSinFoldOp : PTOp {

	*new {
		^super.newCopyArgs("SINFOLD", 2);
	}

	*foldval { |x| ^ pi *  x.exp }

	instantiate { |args, resources|
		var f = PTSinFoldOp.foldval(args[1].instantiate);
		^( (f*args[0].instantiate).sin) / f;
	}
}

PTCrushOp : PTOp {

	*new {
		^super.newCopyArgs("CRUSH", 2);
	}

	*foldval { |x| ^44100.0 * (0.01/(0.01 + x.abs)) }

	min { |args|
		^args[0].min;
	}

	max { |args|
		^args[0].max;
	}

	rate { ^\audio }

	instantiate { |args, resources|
		var f = PTCrushOp.foldval(args[1].instantiate);
		^SmoothDecimator.ar(args[0].instantiate, f);
	}
}

PTBusOp : PTOp {

	var rate, busses, min, max, lag;

	*new { |name, rate, busses, min= -10, max= 10, lag=nil|
		^super.newCopyArgs(name, 1, rate, busses, min, max, lag);
	}

	check { |args|
		if (args[0].isConstant.not, {
			PTCheckError.new(name ++ " needs constant bus number").throw;
		});
		if (args[0].min >= busses.size, {
			PTCheckError.new(name ++ " max bus number is " ++ busses.size).throw;
		});
	}

	min { |args, resources|
		^min;
	}

	max { |args, resources|
		^max;
	}

	instantiate { |args, resources|
		var n = args[0].min;
		var ret = if (rate == \audio, {InFeedback.ar(busses[n].index, numChannels: 2)}, {busses[n].kr});
		^ if (lag == nil, {ret}, {ret.lag(lag)});
	}

	rate { |args|
		^rate
	}
}

PTBusSendOp : PTOp {

	var rate, busses;

	*new { |name, rate, busses|
		^super.newCopyArgs(name, 2, rate, busses);
	}

	check { |args|
		if (args[0].isConstant.not, {
			PTCheckError.new(name ++ " needs constant bus number").throw;
		});
		if (args[0].min >= busses.size, {
			PTCheckError.new(name ++ " max bus number is " ++ busses.size).throw;
		});
	}

	min { |args, resources|
		^args[1].min;
	}

	max { |args, resources|
		^args[1].max;
	}

	*prepareAudio { |a|
		^if(a.rate != \audio,
				{ K2A.ar(a) },
				{a});
	}

	instantiate { |args, resources|
		var n = args[0].min;
		var a = PTScriptNet.maybeMakeStereo(args[1].instantiate);
		^if (rate == \audio, {
				var aa = PTBusSendOp.prepareAudio(a);
				Out.ar(busses[n], aa);
				aa;
			},
			{ Out.kr(busses[n], a); a});
	}
}

PTToCPSOp : PTOp {

	var rootBus;

	*new { |name, rootBus|
		^super.newCopyArgs(name, 1, rootBus);
	}

	min { ^0.1}
	max { ^10000 }

	instantiate { |args, resources|
		^(rootBus.kr.cpsmidi + args[0].instantiate).midicps;
	}
}

PTToCPSScaleOp : PTToCPSOp {
	var scale;
	*new { |name, rootBus, scale|
		^super.newCopyArgs(name, 1, rootBus, scale);
	}

	instantiate { |args, resources|
		var toKey = DegreeToKey.kr(
			scale.as(LocalBuf),
			args[0].instantiate,
			scale.stepsPerOctave);
		^(rootBus.kr.cpsmidi + toKey).midicps;
	}
}

PTNamedBusOp : PTOp {

	var rate, bus, min, max;

	*new { |name, rate, bus, min= -10, max=10|
		^super.newCopyArgs(name, 0, rate, bus, min, max);
	}

	min { |args, resources|
		^min;
	}

	max { |args, resources|
		^max;
	}

	instantiate { |args, resources|
		^if (rate == \audio, {InFeedback.ar(bus.index, numChannels: 2)}, {bus.kr});
	}

	rate { |args|
		^rate
	}
}

PTNamedLazyBusOp : PTNamedBusOp {

	*new { |name, rate, bus, min= -10, max=10|
		^super.newCopyArgs(name, 0, rate, bus, min, max);
	}

	instantiate { |args, resources|
		^if (rate == \audio, {InFeedback.ar(bus.get.index, numChannels: 2)}, {bus.get.kr});
	}
}

PTLazyBus {
	var server, rate, bus;

	*new { |server, rate|
		^super.newCopyArgs(server, rate, nil);
	}

	get {
		if (bus == nil, {
			bus = Bus.alloc(rate, numChannels: 2, server: server);
		});
		^bus;
	}

	free {
		if (bus != nil, {bus.free});
	}
}

PTNamedBusSendOp : PTOp {

	var rate, bus;

	*new { |name, rate, bus|
		^super.newCopyArgs(name, 1, rate, bus);
	}

	min { |args, resources|
		^args[0].min;
	}

	max { |args, resources|
		^args[0].max;
	}

	instantiate { |args, resources|
		var a = PTScriptNet.maybeMakeStereo(args[0].instantiate);
		^if (rate == \audio, {
				var aa = PTBusSendOp.prepareAudio(a);
				Out.ar(bus, aa);
				aa;
			},
			{ Out.kr(bus, a); a});
	}
}

PTNamedLazyBusSendOp : PTNamedBusSendOp {

	instantiate { |args, resources|
		var a = PTScriptNet.maybeMakeStereo(args[0].instantiate);
		var b = bus.get;
		if (b == nil, {
			Error.new("Oh no bus nil " ++ bus ++ " and its get "  ++ b).throw;
		});
		^if (rate == \audio,{
				var aa = PTBusSendOp.prepareAudio(a);
				Out.ar(b, aa);
				aa;
			},
			{ Out.kr(b, a); a});
	}
}

PTParser {
	var <ops, <>counter;

	*new { |ops|
		^super.newCopyArgs(ops, 0);
	}

	*default {
		^PTParser.new(Dictionary.with(*[
			"IN" -> PTInOp.new(),
			"PI" -> PTConst.new("PI", pi),
			"SIN" -> PTOscOp.new("SIN", 1, SinOsc, SinOsc),
			"PSIN" -> PTOscOpPhase("PSIN", 2, SinOsc, SinOsc),
			"TRI" -> PTOscOp.new("TRI", 1, VarSaw, VarSaw),
			"VSAW" -> PTOscOpWidth.new("VSAW", 2, VarSaw, VarSaw),
			"SAW" -> PTOscOp.new("SAW", 1, Saw, LFSaw),
			"SQUARE" -> PTOscOp.new("SQUARE", 1, Pulse, LFPulse),
			"PULSE" -> PTOscOpWidth.new("PULSE", 2, Pulse, LFPulse),
			"RSTEP" -> PTOscOp.new("RSTEP", 1, LFDNoise0, LFDNoise0),
			"RRAMP" -> PTOscOp.new("RRAMP", 1, LFDNoise1, LFDNoise1),
			"RSMOOTH" -> PTOscOp.new("RSMOOTH", 1, LFDNoise3, LFDNoise3),
			"RSM" -> PTOscOp.new("RSMOOTH", 1, LFDNoise3, LFDNoise3),
			"WHITE" -> PTNoiseOp.new("WHITE", WhiteNoise),
			"BROWN" -> PTNoiseOp.new("BROWN", BrownNoise),
			"PINK" -> PTNoiseOp.new("PINK", PinkNoise),

			"LR" -> PTLROp.new,
			"PAN" -> PTFilterOp.new("PAN", 2, Pan2),
			"MONO" -> PTMonoOp.new,
			"ROT" -> PTRotateOp.new,

			"LPF" -> PTFilterOp.new("LPF", 2, LPF),
			"BPF" -> PTFilterOp.new("BPF", 2, BPF),
			"HPF" -> PTFilterOp.new("HPF", 2, HPF),
			"RLPF" -> PTFilterOp.new("RLPF", 3, RLPF),
			"RHPF" -> PTFilterOp.new("RHPF", 3, RHPF),
			"MOOG" -> PTFilterOp.new("MOOG", 3, MoogFF),
			"LPG" -> PTLpgOp.new,
			"DJF" -> PTDJFOp.new,

			"RING" -> PTFilterOp.new("RING", 3, Ringz),

			"LAG" -> PTFilterOp.new("LAG", 2, Lag),
			"SLEW" -> PTFilterOp.new("SLEW", 3, LagUD),
			"PERC" -> PTScaledEnvOp.new("PERC", 2, { Env.perc }),
			"AR" -> PTAREnvOp.new("AR", 3, {|a| Env.perc(a, 1-a)}),
			"AR.L" -> PTAREnvOp.new("AR.L", 3, {|a| Env.perc(a, 1-a, curve: 0)}),
			"AR.C" -> PTAREnvOp.new("AR.C", 4, {|a, c| Env.perc(a, 1-a, curve: c)}),
			"ADSR" -> PTEnvOp.new("ADSR", 5, {|a, d, s, r| Env.adsr(a, d, s, r)}),

			"XF" -> PTDelegatedOp.new("XF", 3, XFade2),
			"S+H" -> PTFilterOp.new("S+H", 2, Latch),
			"SEL2" -> PTSelectOp.new("SEL2", 3),
			"SEL3" -> PTSelectOp.new("SEL3", 4),
			"SEL4" -> PTSelectOp.new("SEL4", 5),
			"SEL5" -> PTSelectOp.new("SEL5", 6),
			"SEQ2" -> PTSequenceOp.new("SEQ2", 4),
			"SEQ3" -> PTSequenceOp.new("SEQ3", 5),
			"SEQ4" -> PTSequenceOp.new("SEQ4", 6),
			"SEQ5" -> PTSequenceOp.new("SEQ5", 7),

			"CDIV" -> PTFilterOp.new("CDIV", 2, PulseDivider),
			"DUR" -> PT01DelegatedOp("DUR", 2, Trig1),
			"PROB" -> PT01DelegatedOp("PROB", 2, CoinGate),

			"DEL" -> PTDelayOp.new,
			"DEL.F" -> PTAllPassOp.new("DEL.F", CombN, CombL),
			"DEL.A" -> PTAllPassOp.new("DEL.A", AllpassN, AllpassL),

			"ABS" -> PTAbsOp.new,
			"INV" -> PTInvOp.new,
			"WRAP" -> PTWrapOp.new,
			"WRP" -> PTWrapOp.new,
			"POS" -> PTPosOp.new,
			"CLIP" -> PTClipOp.new,
			"MIN" -> PTMinOp.new,
			"MAX" -> PTMaxOp.new,
			"SCL" -> PTScaleOp.new,
			"SCL.V" -> PTSclVOp.new,
			"SCL.F" -> PTSclFOp.new,
			"SCL.X" -> PTScaleExpOp.new,
			"BI" -> PTBiOp.new,
			"UNI" -> PTUniOp.new,
			"FLOOR" -> PTFloorOp.new,
			"TANH" -> PTTanhOp.new,
			"FOLD" -> PTFoldOp.new,
			"SINFOLD" -> PTSinFoldOp.new,
			"CRUSH" -> PTCrushOp.new,

			"SILENCE" -> PTSilenceOp.new,
			"+" -> PTPlusOp.new("+", 2),
			"*" -> PTTimesOp.new(),
			"-" -> PTMinusOp.new("-", 2),
			"/" -> PTDivOp.new(),
			"%" -> PTModOp.new(),
			">" -> PTGTOp.new,
			"<" -> PTLTOp.new,
		]));
	}

	parse { |str, context=nil|
		var ctx = context ? (callSite: nil);
		var s = if ( (str == nil) || (str == ""), {"IT"}, {str});
		var sides = s.split($:);
		var preTokens, a, end, tokens;
		counter = counter + 1;
		PTDbg << "parse " << counter << "\n";
		if (sides.size == 1, {
			tokens = sides[0].split($ );
			a = this.parseHelper(tokens, 0, ctx);
			end = a.key;
		}, {
			tokens = sides[1].split($ );
			preTokens = sides[0].split($ );
			case
			{(preTokens[0] == "L.MIX") || (preTokens[0] == "L.M")} {
				var low = this.parseHelper(preTokens, 1, ctx);
				var high = this.parseHelper(preTokens, low.key, ctx);
				var results = List.new;
				var newNode;
				PTDbg << "low " << low << " high " << high << "\n";
				if (low.value.isConstant.not || high.value.isConstant.not || (high.value.min <= low.value.min), {
					PTParseError.new("L.MIX takes two constants").throw;
				});
				if (high.key < preTokens.size, {
					PTParseError.new("Expected :, found " ++ preTokens[high.key]).throw;
				});
				(1 + high.value.min - low.value.min).do { |x|
					var i = x + low.value.min;
					var subctx = (I: PTConst.new("I", i));
					var elt;
					subctx.parent = ctx;
					elt = this.parseHelper(tokens, 0, subctx);
					end = elt.key;
					results.add(elt.value);
				};
				newNode = PTNode.new(PTMixOp.new("L.MIX", results.size), results, ctx['callSite']);
				a = (end -> newNode);
			}
			{true} {
				PTParseError.new("Unknown PRE: " ++ preTokens[0]).throw;
			};
		});
		while({end < tokens.size}, {
			if (tokens[end] != "", {
				PTParseError.new("Unexpected " ++ tokens[end] ++ "; expected end").throw;
			});
			end = end + 1;
		});
		^a.value;
	}

	parseHelper {|tokens, pos, context|
		var newNode;
		PTDbg << "parse helper " << counter << " pos " << pos << "\n";
		counter = counter + 1;
		if (PTDbg.slow && (counter > 6000), {
			Error.new("Something blew up in the parser").throw;
		});
		^case
		{pos >= tokens.size} { PTParseError.new("Expected token; got EOF").throw }
		{"^-?[0-9]+\\.?[0-9]*$".matchRegexp(tokens[pos]) || "^\\.[0-9]+$".matchRegexp(tokens[pos])} {
			newNode = PTNode.new(PTLiteral.new(tokens[pos].asFloat()), [], callSite: context.callSite);
			pos+1 -> newNode
		}
		{ (context ? ()).includesKey(tokens[pos].asSymbol)} {
			var op = context[tokens[pos].asSymbol];
			var p = pos + 1;
			var myArgs = Array.new(maxSize: op.nargs);
			{myArgs.size < op.nargs}.while({
				var a;
				if(p >= tokens.size, {
					PTParseError.new(op.name ++ " expects " ++ op.nargs ++ " args").throw
				});
				try {
					a = this.parseHelper(tokens, p, context);
				} { |e|
					myArgs.do { |a| a.free };
					e.throw;
				};
				myArgs = myArgs.add(a.value);
				p = a.key;
			});
			newNode = PTNode.new(op, myArgs, callSite: context.callSite);
			p -> newNode
		}
		{ops.includesKey(tokens[pos])} {
			var op = ops[tokens[pos]];
			var p = pos + 1;
			var myArgs = Array.new(maxSize: op.nargs);
			{myArgs.size < op.nargs}.while({
				var a;
				if(p >= tokens.size, {
					PTParseError.new(op.name ++ " expects " ++ op.nargs ++ " args").throw
				});
				try {
					a = this.parseHelper(tokens, p, context);
				} { |e|
					myArgs.do { |a| a.free };
					e.throw;
				};
				myArgs = myArgs.add(a.value);
				p = a.key;
			});
			newNode = PTNode.new(op, myArgs, callSite: context.callSite);
			p -> newNode
		}
		{tokens[pos] == ""} {
			this.parseHelper(tokens, pos+1, context)
		}
		{true} {
			var c = context;
			while({context != nil},{
				context = context.parent;
			});
			PTParseError.new("Unknown word: " ++ tokens[pos] ++ ".").throw;
		};
	}
}

PTLine {
	var <>id, <>newLine, <>line, <>newNode, <>node, <>proxy, <>fadeTime, <>quant, <>connected, <>connectRate;

	*new { |newLine, newNode, id=nil, fadeTime=0.01, quant=0|
		^super.newCopyArgs(id ? PT.randId, newLine, nil, newNode, nil, nil, fadeTime, quant, nil, nil);
	}

	maybeConnectIt { |other, deferrals|
		var timeToFree = TempoClock.beats;
		case (
			{other == nil}, {},
			{(newNode ? node).usesIt && (connected == nil)}, {
				// If unconnected, and we need a connection, connect
				PTDbg << "Connecting for the first time\n";
				proxy.set(\in, other.proxy);
				connected = other.id;
				connectRate = other.proxy.rate;
			},
			{(connected != nil) && ((connected != other.id) || (connectRate != other.proxy.rate))}, {
				// If connected and the connection quality changed, what to do depends on whether we would use a connection.
				var xsetToThis;
				if ((newNode ? node).usesIt, {
					PTDbg << "Connecting new node or rate\n";
					connected = other.id;
					connectRate = other.proxy.rate;
					xsetToThis = other.proxy;
				}, {
					PTDbg << "Disconnecting for now\n";
					connected = nil;
					connectRate = nil;
					xsetToThis = nil;
				});
				timeToFree = this.timeToFree;
				PTDbg << "Resheduling free for " << timeToFree << "\n";
				deferrals.add( {proxy.xset(\in, xsetToThis)});
			},
			{PTDbg << "No connection necessary\n"}
		);
		^timeToFree;
	}

	timeToFree {
		^TempoClock.nextTimeOnGrid(quant: quant ? 0) + (TempoClock.tempo * (fadeTime ? 0));
	}

	usesIt {
		^if ((newNode ? node) == nil, {true}, {(newNode ? node).usesIt});
	}
}

PTScriptNet {
	var server, <parser, <order, <newOrder, <dict, <id, <script, <args, <argProxies, <callSite, <jBus, <kBus;

	*new { |server, parser, lines, args=nil, script=nil, callSite|
		var i;
		var o;
		var aa = List.newFrom(args ? []);
		// Need to evaluate args *in the context of the call site* but *only in the commit phase*
		while {aa.size < 4} {aa.add(PT.zeroNode)};
		^super.newCopyArgs(server, parser,
			List.newUsing(["in", "out"]),
			nil,
			Dictionary.newFrom([
				// Possibly don't need an in node anymore, as long as we make the right context for each line.
				"in", PTLine.new("I1", PTNode.new(PTArgOp("I1", \i1, aa[0].rate)), "in"),
				"out", PTLine.new("IT", PTNode.new(PTArgOp("IT", \in, aa[0].rate)), "out"),
		]), PT.randId, script, aa, nil, callSite, nil, nil).init(lines);
	}

	*maybeMakeStereo { |ugen|
		var u = ugen;
		if (ugen.class == Function, {
			u = ugen.value;
		});
		^if (ugen.size == 0, {
			ugen!2
		}, {ugen});
	}

	prevEntryOf { |id|
		var o = newOrder ? order;
		var idx = o.indexOf(id);
		var prevId = o[idx-1];
		^if (prevId == nil, {dict["in"]}, {dict[prevId]});
	}

	initArgProxies {
		argProxies = List.new;
		4.do { |i|
			var a = args[i];
			var n = if (callSite != nil, {
				var p;
				PTDbg << "New proxy on init for " << this << "\n";
				p = callSite.net.newProxy(rate: nil, fadeTime: 0, quant: 0);
				p.set(\in, callSite.net.prevEntryOf(callSite.id).proxy);
				p;
			}, {
				NodeProxy.new(
					server,
					rate: if(a != nil, {a.rate}, {\control}),
					numChannels: if(a != nil, {2}, {1}))
			});
			if (
				a != nil,
				{
					PTDbg << "Setting arg source to " << a << "\n";
					n.source = { PTScriptNet.maybeMakeStereo(a.instantiate) };
				},
				{ n.source = {0.0} }
			);
			argProxies.add(n);
		};
	}

	// Get a context for evaluation where the previous line has rate r.
	contextWithItRate { |r, id|
		var ret = (
			I1: PTArgOp("I1", \i1, args[0].rate, args[0].min, args[0].max),
			I2: PTArgOp("I2", \i2, args[1].rate, args[1].min, args[1].max),
			I3: PTArgOp("I3", \i3, args[2].rate, args[2].min, args[2].max),
			I4: PTArgOp("I4", \i1, args[3].rate, args[3].min, args[3].max),
			IT: PTArgOp("IT", \in, r),
			'IT.F': PTArgOp("IT.F", \in, r, 20, 20000),
			'IT.U': PTArgOp("IT.F", \in, r, 0, 1),
			'IT.B': PTArgOp("IT.F", \in, r, -1, 1),
			J: PTNamedLazyBusOp("J", \audio, jBus),
			K: PTNamedLazyBusOp("K", \control, kBus),
			'J=': PTNamedLazyBusSendOp("J", \audio, jBus),
			'K=': PTNamedLazyBusSendOp("K", \control, kBus),
			callSite: (net: this, id: id),
		);
		if (script != nil, {ret.parent = script.context});
		^ret;
	}

	newProxy { |rate=nil, fadeTime, quant|
		var ret = NodeProxy.new(server, rate: rate, numChannels: 2);
		ret.fadeTime = fadeTime;
		if (quant != nil, {
			ret.quant = Quant.new(quant, -0.01);
		});
		ret.set(\i1, argProxies[0]);
		ret.set(\i2, argProxies[1]);
		ret.set(\i3, argProxies[2]);
		ret.set(\i4, argProxies[3]);
		^ret;
	}

	startEdit {
		newOrder = List.newFrom(order);
	}

	assertEditing {
		if (newOrder == nil, {
			Error.new("I thought we were editing").throw;
		});
	}

	init { |l|
		if (script != nil, {script.refs[id] = this});
		try {
			this.startEdit;
			jBus = PTLazyBus.new(server, \audio);
			kBus = PTLazyBus(server, \control);
			if (script != nil, {
				PTDbg << "Initializing net " << id << " from script " << script << script.linesOrDraft << "\n";
				script.linesOrDraft.do { |x|
					PTDbg << "Adding on init " << x << " to " << id << "\n";
					this.stageAdd(x);
				};
			}, {
				l.do { |x| this.stageAdd(x) };
			});
		} { |e|
			this.free;
			e.throw;
		};
	}

	lines {
		^order.collect({|x| dict[x].line}).reject({|x| x == nil});
	}

	out {
		^dict[order.last].proxy;
	}

	printOn { | stream |
        stream << "PTScriptNet(\n";
		stream << id << "\n";
		stream << "order " << order << "\n";
		stream << "newOrder " << newOrder << "\n";
		stream << this.dict;
		stream << ")\n";
    }

	*makeOut { |out, rate|
		case { rate == \audio } {
			out.source = { \in.ar([0, 0]) };
		}
		{ rate == \control } {
			out.source = { \in.kr([0, 0]) };
		}
		{ true } {
			Error.new("Unknown output rate for script").throw;
		};
	}

	*nodeOf { |entry| ^entry.newNode ? entry.node }

	stageAdd { |line, fadeTime, quant|
		this.assertEditing;
		^this.stageInsert(newOrder.size - 1, line, fadeTime, quant);
	}

	stageInsertPassthrough { |index, fadeTime, quant|
		var id = PT.randId;
		var prevEntry = this[index-1];
		var nextEntry = this[index];
		var parsed = parser.parse("IT", this.contextWithItRate(PTScriptNet.nodeOf(prevEntry).rate, id: id));
		var entry = PTLine.new("IT", parsed, id, fadeTime, quant);
		this.assertEditing;
		dict[id] = entry;
		newOrder.insert(index, id);
	}

	stageInsert { |index, line, fadeTime, quant|
		this.assertEditing;
		this.stageInsertPassthrough(index, fadeTime, quant);
		^this.stageReplace(index, line);
	}

	at { |index|
		^if(index.class === String, {dict[index]}, {dict[(newOrder ? order)[index]]});
	}

	newOutputRate {
		^dict[newOrder.last].newNode.rate;
	}

	outputRate {
		^dict[order.last].node.rate;
	}

	rate {
		^(this.newOutputRate ? this.outputRate);
	}

	stageRemoveAt { |index|
		var prev = this[index-1];
		var next = this[index+1];
		var toRemove = this[index];
		var id = order[index];
		var propagate = (PTScriptNet.nodeOf(prev).rate != PTScriptNet.nodeOf(toRemove).rate);
		var i = index + 1;
		this.assertEditing;
		newOrder.removeAt(index);
		^if (propagate, {
			this.stageReplace(index, next.newLine ? next.line);
		}, {
			this;
		});
	}

	outputChanged {
		// PTDbg << "output rate is " << this.outputRate << " new output rate is " << this.newOutputRate << "\n";
		^(this.outputRate != nil) && (this.newOutputRate != nil) && (this.newOutputRate != this.outputRate);
	}

	// Reevaluate the entry at id. If it's already being reevaluated (because, say, the user changed it), return nil.
	reevaluate { |id|
		var entry = this[id];
		var idx = newOrder.indexOf(id);
		^if (entry.newLine == nil, {
			this.stageReplace(idx, entry.newLine ? entry.line);
		}, {
			PTDbg << "We are already being reevaluated\n";
			nil
		});
	}

	stageReplace { |idx, line|
		var id, entry, prev, next, propagate, parsedLine;
		this.assertEditing;
		id = newOrder[idx];
		if (id == nil, {
			Error.new("Bad replace id " ++ idx ++ " size " ++ newOrder.size).throw;
		});
		entry = dict[id];
		if (entry == nil, {
			Error.new("No entry " ++ id ++ " in dict " ++ dict).throw;
		});
		prev = this[idx-1];
		next = this[idx+1];
		entry.newLine = line;
		if (entry.newNode != nil, {
			PTDbg << "Found it -- replaceing a new node with another" << entry.newNode <<"\n";
			entry.newNode.free;
		});
		PTDbg << "Replace gonna parse\n";
		parsedLine = parser.parse(line, context: this.contextWithItRate(PTScriptNet.nodeOf(prev).rate, id: id));
		PTDbg << "Replace parsed\n";
		entry.newNode = parsedLine;
		propagate = false;
		case (
			{entry.node == nil}, { propagate = true;},
			{entry.node.rate != entry.newNode.rate}, {propagate = true;},
		);
		^if (propagate && (next != nil), {
			PTDbg.complex;
			if (next.usesIt, {
				PTDbg << "reevaluating next line " << (idx+1) << "\n";
				this.stageReplace(idx+1, next.newLine ? next.line);
			}, {this});
		}, {
			if (this.outputChanged && (callSite != nil), {
				PTDbg << "reevaluating call site of " << this.id << "\n";
				// When we reevaluate the call site, it could *already* be
				// part of this mess of things we're attempting to do and commit.
				// In that case, reevaluate is designed to return nil.
				PTDbg.complex;
				callSite.net.reevaluate(callSite.id);
			}, {
				this
			});
		});
	}

	setFadeTime { |index, time|
		var node = dict[order[index]];
		node.fadeTime = time;
		if (node.proxy != nil, {
			node.proxy.fadeTime = time;
		});
	}

	setQuant { |index, quant|
		var node = dict[order[index]];
		node.quant = quant;
		if (node.proxy != nil, {
			node.proxy.quant = Quant.new(quant, -0.01);
		});
	}

	abort {
		order.do { |id|
			var entry = dict[id];
			PTDbg.complex;
			if( entry.newNode != nil, {
				entry.newNode.free;
			});
			entry.newLine = nil;
			entry.newNode = nil;
		};
		// At this point anything in newOrder w a newNode is *not* in order
		newOrder.do { |id|
			var entry = dict[id];
			PTDbg.complex;
			if (entry.newNode != nil, {
				entry.newNode.free;
				dict.removeAt(id);
			});
		};
		newOrder = nil;
	}

	commit { |cb|
		var outEntry = dict[newOrder.last];
		if (argProxies == nil, {
			PTDbg << "INITIALIZING ARG PROXIES\n";
			this.initArgProxies;
		});
		^Routine.new({
			var freeProxies = List.new;
			var freeNodes = List.new;
			var prevEntry = nil;
			var prevId = nil;
			var prevProxyIsNew = false;
			var connect;
			var timeToFree = TempoClock.beats;
			var entriesToLeaveBehind;
			var deferredConnections = List.new;
			var freeFn;
			// Stage 1: allocate all the new node proxies, and connect them together.
			PTDbg << "Beginning commit routine for scriptNet " << id << "\n";
			server.sync;

			newOrder.do { |id, idx|
				var entry = dict[id];
				var node = PTScriptNet.nodeOf(entry);
				var oldIdx = order.indexOf(id);
				// Allocate a proxy if needed
				var proxyIsNew = false;
				var oldPreviousWasDifferent = false;
				if (PTDbg.slow && (1.0.rand < 0.05), { (0.05).yield });
				PTDbg.complex;
				case (
					{entry.proxy == nil}, {
						// New entry
						var newP;
						PTDbg << "new proxy for " << idx << " due to newness " << node.rate << "\n";
						newP = this.newProxy(node.rate, entry.fadeTime, entry.quant);
						entry.proxy = newP;
						proxyIsNew = true;
						if (node.rate == nil, {
							PTDbg << "Nil rate node!! " << node << "\n";
						});
					},
					{entry.proxy.rate != node.rate}, {
						var newP;
						// Rate change entry
						// Schedule the old proxy for freeing
						freeProxies.add(entry.proxy);
						// Make the new one.
						PTDbg << "new proxy for " << idx << " due to rate change " << node.rate << "\n";
						newP = this.newProxy(node.rate, entry.fadeTime, entry.quant);
						entry.proxy = newP;
						proxyIsNew = true;
					},
					{ oldIdx == nil }, {},
					{ (oldIdx != nil) && (oldIdx > 0) && (order[oldIdx-1] != prevId) }, {
						// Possibly removed entry
						oldPreviousWasDifferent = true;
					}
				);
				timeToFree = max(timeToFree, entry.maybeConnectIt(prevEntry, deferredConnections));
				prevProxyIsNew = proxyIsNew;
				prevId = id;
				prevEntry = entry;
			};
			server.sync;
			// Stage 2: Set the source of all the node proxies.
			PTDbg << "Instantiating nodes for " << newOrder << "\n";
			newOrder.do { |id|
				var entry = dict[id];
				if (PTDbg.slow && (1.0.rand < 0.05), { (0.05).yield });
				PTDbg.complex;
				if (entry.newNode != nil, {
					PTDbg << "Committing new node " << entry.newNode << "\n";
					entry.newNode.commit;
					PTDbg << "Scheduling for free " << entry.node << " because we have " << entry.newNode << "\n";
					freeNodes.add(entry.node);
					timeToFree = max(timeToFree, entry.timeToFree);
					if (entry.newNode == nil, {
						PTDbg << "WTF " << entry << "\n" << this << "\n";
					});
					PTDbg << "Instantiating source for " << id << " to be " << entry.newNode << "\n";
					entry.proxy.source = { PTScriptNet.maybeMakeStereo(entry.newNode.instantiate) };
					entry.node = entry.newNode;
					entry.line = entry.newLine;
					entry.newNode = nil;
					entry.newLine = nil;
				});
			};
			server.sync;
			0.07.yield;
			// Stage 3: Connect new inputs to any "live" proxies
			PTDbg << "Deferred connecting proxies " << deferredConnections << "\n";
			deferredConnections.do { |x| x.value };
			// Stage 4: Collect anything no longer needed. Exit the transaction.
			entriesToLeaveBehind = order.reject({|x| newOrder.includes(x)});
			entriesToLeaveBehind.do { |id|
				var entry = dict[id];
				PTDbg.complex;
				freeNodes.add(entry.node);
				freeProxies.add(entry.proxy);
				dict.removeAt(id);
			};
			order = newOrder;
			// Indicate we are done with everything but cleanup
			cb.value;
			server.sync;
			freeFn = {
				// Stage 5, later: free some stuff
				freeNodes.do({|x| x.free});
				freeProxies.do({|x|
					PTDbg << "Freeing proxy\n";
					x.clear;
				});
			};
			// If we needed to fade a proxy, schedule the free for after the fade.
			PTDbg << "It is " << TempoClock.beats << " and we will free things at " << (timeToFree + server.latency) << "\n";
			TempoClock.schedAbs(timeToFree + server.latency, freeFn);
		});
	}

	free {
		// clear all my proxies, free all my nodes
		dict.do { |entry|
			entry.proxy.clear;
			entry.node.free;
			entry.newNode.free;

		};

		argProxies.do { |p| p.clear };
		jBus.free;
		kBus.free;
		// remove myself from ref tracking.
		if (script != nil, {script.refs.removeAt(id)});
	}

}

PTFreer {
	var f;

	*new {|f|
		^super.newCopyArgs(f);
	}

	free {
		f.value;
	}
}

PTCountdownLatch {
	var n, cb, id;
	*new { |n, cb|
		^super.newCopyArgs(n, cb, PT.randId).init;
	}

	init {
		// PTDbg << "Initialize latch " << id << " with " << n << "\n";
		if (n == 0, {
			SystemClock.sched(0, {
				// PTDbg << "Boom " << id << "\n";
				cb.value;
			});
		});
	}

	value {
		n = n - 1;
		if (n == 0, {
			// PTDbg << "Bang " << id << "\n";
			cb.value;
		}, {
			// PTDbg << "Tick " << n << id << "\n";
		});
	}
}

PTRhythmOp : PTOp {
	var server, quant, phase;

	*new { |name, nargs, server, quant, phase=0|
		^super.newCopyArgs(name, nargs, server, quant, phase)
	}

	min { ^0 }

	max { ^1 }

	rate { ^\control }

	alloc { |args, callSite|
		^[nil, nil];
	}

	instantiate { |args, resources|
		var b, idx, esp, freer, pattern;
		var q = Quant.new(quant, phase: phase);
		if (resources[0] == nil, {
			b = Bus.control(server, numChannels: 1);
			pattern = Pbind(\instrument, \tick, \dur, quant, \bus, b.index);
			PTDbg << "Bus " << b << " server " << server << "\n";
			idx = b.index;
			if (quant == 0, { Error.new("OOPOS quant zero").throw; });
			esp = pattern.play(TempoClock.default, quant: q);
			PTDbg << "Starting beat" << idx << "\n";
			freer = PTFreer({
				PTDbg << "Stopping beat" << idx << "\n";
				esp.stop;
			});
			resources[0] = b;
			resources[1] = freer;
		}, {
			b = resources[0];
		});
		^b.kr;
	}
}

PTEuclideanOp : PTOp {
	var server;

	*new { |server|
		// Args are fill, len, offset, duration (per-beat)
		^super.newCopyArgs("ER", 4, server)
	}

	min { ^0 }

	max { ^1 }

	rate { ^\control }

	alloc { |args, callSite|
		// Resources will be filled with:
		// 0: result bus
		// 1: length and offset bus
		// 2: synth that sets length and offset bus
		// 3: freer of pbind
		^[nil, nil, nil, nil];
	}

	check { |args|
		args[1].isConstant.not.if {
			PTCheckError.new("ER length must be constant").throw;
		};
		args[3].isConstant.not.if {
			PTCheckError.new("ER duration must be constant").throw;
		};
	}

	mono {|x|
		^if (x.size == 0) {x} {x[0]}
	}

	getDur { |args|
		^args[3].min;
	}

	instantiate { |args, resources|
		var lenInNotes = args[1].min;
		var dur = this.getDur(args);
		var length = lenInNotes*dur;
		var p, b, idx, esp, freer, pattern;
		var q = Quant.new(length);
		var beats = (Rest(dur))!lenInNotes;

		var getter = { |arr|
			var fill = arr[0].floor.asInteger;
			var offset = arr[1].floor.asInteger;
			beats = (fill / lenInNotes * (0..lenInNotes - 1)).floor.differentiate.asInteger.min(1)[0] = if (fill <= 0) { 0 } { 1 };
			beats = beats.rotate(offset);
		};

		var euclideanRoutine = Routine({
			while {true} {
				beats.size.do { |i|
					p.get(getter);
					if(beats[i] == 0, {
						Rest(dur).yield;
					}, {
						dur.yield;
					});
				};
			};
		});

		// Initialize beats with min fill and offset
		getter.value([args[0].min, args[2].min]);

		if (resources[0] == nil, {
			b = Bus.control(server, numChannels: 1);
			p = Bus.control(server, numChannels: 2);
			pattern = Pbind(
				\instrument, \tick, \dur, euclideanRoutine,
				\bus, b.index,
			);
			idx = b.index;
			esp = pattern.play(TempoClock.default, quant: q);
			PTDbg << "Starting euclidean" << idx << "\n";
			freer = PTFreer({
				PTDbg << "Stopping euclidean" << idx << "\n";
				esp.stop;
			});
			resources[0] = b;
			resources[1] = p;
			PTDbg << "Making\n";
			resources[2] = NodeProxy.new;
			resources[2].source = {
				var fill = this.mono(args[0].instantiate);
				var offset = this.mono(args[2].instantiate);
				Out.kr(p, [fill, offset]);
			};
			resources[3] = freer;
			p.get(getter);
		}, {
			b = resources[0];
		});
		^b.kr;
	}
}

PTConstEuclideanOp : PTEuclideanOp {

	var dur;

	*new { |name, server, dur|
		// Args are fill, len, offset, duration (per-beat)
		^super.newCopyArgs(name, 3, server, dur)
	}

	getDur { |args|
		^dur;
	}

	check { |args|
		args[1].isConstant.not.if {
			PTCheckError.new("ER length must be constant").throw;
		};
	}
}

PTEveryOp : PTOp {
	var server;

	*new { |name, server|
		^super.newCopyArgs(name, 2, server)
	}

	min { ^0 }

	max { ^1 }

	check { |args|
		if (args[0].isConstant.not || args[1].isConstant.not, {
			PTCheckError.new("EVERY args must be constant");
		});
	}

	rate { ^\control }

	alloc { |args, callSite|
		^[nil, nil];
	}

	instantiate { |args, resources|
		var b, idx, esp, freer, pattern;
		var quant = args[0].min;
		var phase = args[1].min;
		var q = Quant.new(quant, phase: phase);
		if (resources[0] == nil, {
			b = Bus.control(server, numChannels: 1);
			pattern = Pbind(\instrument, \tick, \dur, quant, \bus, b.index);
			PTDbg << "Bus " << b << " server " << server << "\n";
			idx = b.index;
			if (quant == 0, { Error.new("OOPOS quant zero").throw; });
			esp = pattern.play(TempoClock.default, quant: q);
			PTDbg << "Starting beat" << idx << "\n";
			freer = PTFreer({
				PTDbg << "Stopping beat" << idx << "\n";
				esp.stop;
			});
			resources[0] = b;
			resources[1] = freer;
		}, {
			b = resources[0];
		});
		^b.kr;
	}
}

PTScriptOp : PTOp {
	var server, parser, script;

	*new { |server, name, nargs, parser, script|
		^super.newCopyArgs(name, nargs, server, parser, script);
	}

	stringWithArgsResources { |args, resources|
		^if (resources == nil, {
			"Empty PTScriptOp";
		}, {
			var net = resources[0];
			"Script: " ++ net.id;
		});
	}

	min { |args, resources|
		^-10;
	}

	max { |args, resources|
		^10;
	}

	rate { |args, resources|
		var net = resources[0];
		^net.rate;
	}

	alloc { |args, callSite|
		var net = PTScriptNet.new(
			server: server, parser: parser,
			lines: script.linesOrDraft, args: args,
			script: script, callSite: callSite);
		^[net];
	}

	commit { |args, resources|
		var net = resources[0];
		PTDbg << "Committing args " << args << "\n";
		args.do { |a|
			a.commit;
		};
		PTDbg << "Committing net from op " << net.id << "\n";
		net.commit.do { |w| w.yield };
	}

	instantiate { |args, resources|
		var net = resources[0];
		^switch (net.out.rate,
			\audio, { net.out.ar },
			\control, { net.out.kr },
			{ Error.new("Unknown rate").throw },
		);
	}
}

PTDescription {
	var <size, <lines;

	*new { |size|
		^super.newCopyArgs(size, List.new);
	}

	linesOrDraft {
		^lines
	}

	load { |newLines, topLevel=false, callback|
		lines.clear.addAll(newLines);
		callback.value;
	}

	add { |line, topLevel=false, callback|
		lines.add(line);
		callback.value;
	}

	validateIndex { |index, allowSize=true|
		if (index < 0, { PTEditError.new("Index must be > 0").throw });
		if (index > lines.size, { PTEditError.new("Index must be < number of lines").throw });
		if ((index == lines.size) && (allowSize.not), { PTEditError.new("Cant operate on index " ++ index).throw });
	}

	insertPassthrough { |index, topLevel=false, callback|
		lines.insert(index, "");
		callback.value;
	}

	removeAt { |index, topLevel=false, callback=nil|
		this.validateIndex(index);
		lines.removeAt(index);
		callback.value;
	}

	replace { |index, line, topLevel=false, callback=nil|
		this.validateIndex(index);
		lines[index] = line;
		callback.value;
	}

	setFadeTime { |index, time|}

	getFadeTime { |index|
		^0;
	}

	setQuant { |index, q|}

	getQuant { |index|
		^1;
	}

	clear { |topLevel=false, callback|
		lines.clear;
		callback.value;
	}
}

PTScript {
	var <size, <lines, <fadeTimes, <quants, <refs, <context, <linesDraft, working;

	*new { |size, context|
		^super.newCopyArgs(size, List.new, List.new, List.new, Dictionary.new, context, nil, false);
	}

	linesOrDraft {
		^(linesDraft ? lines)
	}

	defaultFadeTime {
		^if (context.includesKey('defaultFadeTime'), {context['defaultFadeTime']}, {0.01});
	}

	defaultQuant {
		^if (context.includesKey('defaultQuant'), {context['defaultQuant']}, {1/16});
	}

	load { |newLines, topLevel=false, callback|
		var newFadeTimes = List.new;
		var newQuants = List.new;
		var newLinesActual = List.new;
		PTDbg << "load new lines " << newLines << "\n";
		linesDraft = List.newFrom(lines);
		newLines.do { |line|
			var commaSep = line.split($,);
			linesDraft.add(commaSep[0]);
			newLinesActual.add(commaSep[0]);
			newFadeTimes.add((commaSep[1] ? this.defaultFadeTime).asFloat);
			newQuants.add((commaSep[2] ? this.defaultQuant).asFloat);
		};
		this.makeHappen({ |net|
			net.startEdit;
			newLinesActual.do {|line, i|
				PTDbg << "loading line " << line << "\n";
				net.stageAdd(line, newFadeTimes[i], newQuants[i]);
			};
			// Return the net we staged
			net
		}, topLevel, callback, "load");
		newFadeTimes.do { |x|
			fadeTimes.add(x);
		};
		newQuants.do { |x|
			quants.add(x);
		};
	}

	add { |line, topLevel=false, callback|
		linesDraft = List.newFrom(lines);
		linesDraft.add(line);
		this.makeHappen({ |net|
			net.startEdit;
			net.stageAdd(line, this.defaultFadeTime, this.defaultQuant);
		}, topLevel, callback, "add");
		fadeTimes.add(this.defaultFadeTime);
		quants.add(this.defaultQuant);
	}

	validateIndex { |index, allowSize=true|
		if (index < 0, { PTEditError.new("Index must be >= 0").throw });
		if (index > lines.size, { PTEditError.new("Index must be < number of lines").throw });
		if ((index == lines.size) && (allowSize.not), { PTEditError.new("Cant operate on index " ++ index).throw });
	}

	insertPassthrough { |index, topLevel=false, callback|
		if (lines.size >= size, {
			PTEditError.new("Can't insert another line").throw
		});
		this.validateIndex(index);
		linesDraft = List.newFrom(lines);
		linesDraft.insert(index, "IT");
		this.makeHappen({ |net|
			net.startEdit;
			net.stageInsertPassthrough(index+1, this.defaultFadeTime, this.defaultQuant);
		}, topLevel, callback, "insertPassthrough");
		// Inserting a passthrough should never fail.
		fadeTimes.insert(index, this.defaultFadeTime);
		quants.insert(index, this.defaultQuant);
	}

	makeHappen { |f, topLevel, callback, from|
		var toCommit = List.new;
		var latch;
		// if (working, {
		//	Error.new("OMG IM WORKING").throw;
		// });
		working = true;
		try {
			var todo = List.newFrom(refs);
			PTDbg << "staging change to " << todo.size << "\n";
			todo.do { |r|
				PTDbg.complex;
				try {
					var candidate;
					PTDbg << "in stage loop\n";
					candidate = f.value(r);
					PTDbg << "got candidate\n";
					if (candidate != nil, {toCommit.add(candidate)});
				} { |err|
					// If we error in the middle of adjusting a net, we need to abort that net too, along with any others.
					r.abort;
					err.throw;
				};
			};
			PTDbg << "staged " << todo.size << "\n";
			// PTDbg << "Check top level\n";
			if (topLevel && (toCommit.select({|p| p.outputChanged}).size > 0), {
				PTCheckError.new("Output must be audio").throw;
			});
		} { |err|
			Post << "Aborting; this error may be expected: \n";
			err.reportError;
			toCommit.do { |p|
				if (p == nil, {
					Post << "Thing to commit is nil?\n"
				}, {
					PTDbg << "aborting\n";
					p.abort;
				});
			};
			linesDraft = nil;
			working = false;
			err.throw;
		};
		//PTDbg << "committing to lines " << linesDraft << "\n";
		lines = linesDraft;
		linesDraft = nil;
		// PTDbg << "new latch of size " << toCommit.size << " and callback " << callback << "\n";
		latch = PTCountdownLatch.new(toCommit.size, {
			working = false;
			callback.value;
		});
		PTDbg << "About to commit asynchronously " << from << " " << toCommit << "\n";
		toCommit.do { |p|
			PTDbg << "Committing " << p.id << " from makeHappen\n";
			p.commit(latch).play;
		};
	}

	removeAt { |index, topLevel=false, callback=nil|
		this.validateIndex(index, allowSize: false);
		linesDraft = List.newFrom(lines);
		linesDraft.removeAt(index);
		this.makeHappen({ |r|
			r.startEdit;
			r.stageRemoveAt(index+1);
		}, topLevel, callback, "removeAt");
		fadeTimes.removeAt(index);
		quants.removeAt(index);
	}

	replace { |index, line, topLevel=false, callback=nil|
		this.validateIndex(index, allowSize: false);
		linesDraft = List.newFrom(lines);
		linesDraft[index] = line;
		this.makeHappen({ |r|
			PTDbg << "replace starting edit\n";
			r.startEdit;
			PTDbg << "replace stage replace\n";
			r.stageReplace(index+1, line)
		}, topLevel, callback, "replace");
		lines[index] = line;
	}

	setFadeTime { |index, time|
		this.validateIndex(index);
		refs.do { |r| r.setFadeTime(index+1, time) };
		fadeTimes[index] = time;
	}

	getFadeTime { |index|
		^fadeTimes[index];
	}

	setQuant { |index, q|
		this.validateIndex(index);
		refs.do { |r| r.setQuant(index+1, q) };
		quants[index] = q;
	}

	getQuant { |index|
		^quants[index];
	}

	clear { |topLevel=false, callback|
		linesDraft = List.new;
		this.makeHappen( {|r|
			r.startEdit;
			(r.lines.size-2).reverseDo { |i|
				r.stageRemoveAt(i+1);
			};
			r;
		}, topLevel, callback, "clear");
		fadeTimes.clear;
		quants.clear;
	}
}

PT {
	const vowels = "aeiou";
	const consonants = "abcdefghijklmnopqrstuvwxyz";
	const numScripts = 9;
	const scriptSize = 6;

	var server, <scripts, <description, <parser, <main, audio_busses, control_busses, param_busses, out_proxy, ctx;

	*new { |server|
		PTDbg << "Adding tick\n";
		SynthDef(\tick, { |bus|
			var env = Env(levels: [0, 1, 0], times: [0, 0.01], curve: 'hold');
			Out.kr(bus, EnvGen.kr(env, doneAction: Done.freeSelf));
		}).add;

		^super.newCopyArgs(server, nil, nil, PTParser.default, nil, nil, nil, nil, nil).init;
	}

	*wrapWithCallbacks { |r, successCallback, errorCallback|
		^Routine.new {
			try {
				var res = r.next;
				while( {res != nil}, {
					res.yield;
					res = r.next;
				});
				successCallback.value;
			} { |err|
				errorCallback.value(err);
			};
		}
	}

	reset { parser.counter = 0; PTDbg.complexity = 0; }

	putBusOps { |ctx, name, bus, rate|
		ctx[name.asSymbol] = PTNamedBusOp.new(name, rate, bus);
		ctx[(name ++ ".F").asSymbol] = PTNamedBusOp.new(name ++ ".F", rate, bus, 20, 20000);
		ctx[(name ++ ".U").asSymbol] = PTNamedBusOp.new(name ++ ".U", rate, bus, 0, 1);
		ctx[(name ++ ".B").asSymbol] = PTNamedBusOp.new(name ++ ".B", rate, bus, -1, 1);
		ctx[(name ++ "=").asSymbol] = PTNamedBusSendOp.new(name ++ "=", rate, bus);
	}

	initBusses { |ctx|
		if (audio_busses == nil, {
			audio_busses = List.new;
			20.do { |i|
				var bus = Bus.audio(server, numChannels: 2);
				audio_busses.add(bus);
			};
		});
		ctx['AB'] = PTBusOp.new("AB", \audio, audio_busses);
		ctx['AB.F'] = PTBusOp.new("AB.F", \audio, audio_busses, 20, 20000);
		ctx['AB.U'] = PTBusOp.new("AB.U", \audio, audio_busses, 0, 1);
		ctx['AB.B'] = PTBusOp.new("AB.B", \audio, audio_busses, -1, 1);
		ctx['AB='] = PTBusSendOp.new("AB=", \audio, audio_busses);
		this.putBusOps(ctx, "A", audio_busses[16], \audio);
		this.putBusOps(ctx, "B", audio_busses[17], \audio);
		this.putBusOps(ctx, "C", audio_busses[18], \audio);
		this.putBusOps(ctx, "D", audio_busses[19], \audio);

		if (control_busses == nil, {
			control_busses = List.new;
			20.do { |i|
				var bus = Bus.control(server, numChannels: 2);
				control_busses.add(bus);
			};
		});
		ctx['CB'] = PTBusOp.new("CB", \control, control_busses);
		ctx['CB.F'] = PTBusOp.new("CB.F", \control, control_busses, 20, 20000);
		ctx['CB.U'] = PTBusOp.new("CB.U", \control, control_busses, 0, 1);
		ctx['CB.B'] = PTBusOp.new("CB.B", \control, control_busses, -1, 1);
		ctx['CB='] = PTBusSendOp.new("CB=", \control, control_busses);
		this.putBusOps(ctx, "W", control_busses[16], \control);
		this.putBusOps(ctx, "X", control_busses[17], \control);
		this.putBusOps(ctx, "Y", control_busses[18], \control);
		this.putBusOps(ctx, "Z", control_busses[19], \control);

		if (param_busses == nil, {
			param_busses = List.new;
			// Special parameter busses:
			// 16 is the duration of a beat (exposed as M)
			// 17 is the root note (exposed as ROOT)
			// 18 is the output gain (not exposed internally)
			// 19 is the Frequency bus
			// 20 is the Gate bus
			// 21 is the Velocity bus.
			22.do { |i|
				var bus = Bus.control(server, numChannels: 2);
				param_busses.add(bus);
			};
			// Default output gain.
			param_busses[18].value = 0.4;
			// default frequency
			param_busses[19].value = 440;
		});
		// 10 ms lag on params so they crunch less with midi controllers
		ctx['PARAM'] = PTBusOp.new("PARAM", \control, param_busses, 0, 1, 0.01);
		ctx['PRM'] = ctx['PARAM'];
		ctx['P'] = ctx['PARAM'];
		ctx['M'] = PTNamedBusOp.new("M", \control, param_busses[16], 0.1, 2);
		ctx['F'] = PTNamedBusOp.new("F", \control, param_busses[19], 20, 10000);
		ctx['G'] = PTNamedBusOp.new("G", \control, param_busses[20], 0, 1);
		ctx['V'] = PTNamedBusOp.new("V", \control, param_busses[21], 0, 1);

		// Set up the note operations
		param_busses[17].value = 440;
		ctx['ROOT'] = PTNamedBusOp.new("ROOT", \control, param_busses[17], 20, 10000);
		ctx['N'] = PTToCPSOp.new("N", param_busses[17]);
		ctx['N.QT'] = PTToCPSScaleOp.new("N.QT", param_busses[17], Scale.chromatic);
		ctx['N.MAJ'] = PTToCPSScaleOp.new("N.MAJ", param_busses[17], Scale.major);
		ctx['N.MIN'] = PTToCPSScaleOp.new("N.MIN", param_busses[17], Scale.minor);
		ctx['N.HM'] = PTToCPSScaleOp.new("N.HM", param_busses[17], Scale.harmonicMinor);
		ctx['N.MAJP'] = PTToCPSScaleOp.new("N.MAJP", param_busses[17], Scale.majorPentatonic);
		ctx['N.MINP'] = PTToCPSScaleOp.new("N.MINP", param_busses[17], Scale.minorPentatonic);
		ctx['N.DOR'] = PTToCPSScaleOp.new("N.DOR", param_busses[17], Scale.dorian);
	}

	initBeats { |ctx|
		ctx[\SN] = PTRhythmOp("SN", 0, server, 0.25);
		ctx[\SNT] = PTRhythmOp("SN", 0, server, 0.25/3);
		ctx[\EN] = PTRhythmOp("EN", 0, server, 0.5);
		ctx[\ENT] = PTRhythmOp("ENT", 0, server, 0.5/3);
		ctx[\QN] = PTRhythmOp("QN", 0, server, 1);
		ctx[\QNT] = PTRhythmOp("QNT", 0, server, 1/3);
		ctx[\HN] = PTRhythmOp("HN", 0, server, 2);
		ctx[\HNT] = PTRhythmOp("HNT", 0, server, 2/3);
		ctx[\WN] = PTRhythmOp("WN", 0, server, 4);
		ctx[\WNT] = PTRhythmOp("WN", 0, server, 4/3);

		ctx[\EVERY] = PTEveryOp("EVERY", server);
		ctx[\EV] = PTEveryOp("EVERY", server);

		ctx[\ER] = PTEuclideanOp(server);
		ctx['SN.ER'] = PTConstEuclideanOp("SN.ER", server, 0.25);
		ctx['EN.ER'] = PTConstEuclideanOp("EN.ER", server, 0.5);
		ctx['QN.ER'] = PTConstEuclideanOp("QN.ER", server, 1);



		4.do { |i|
			var beat = i + 1;
			var name = "BT" ++ beat;
			ctx[name.asSymbol] = PTRhythmOp(name, 0, server, 4, i);
			ctx[(name ++ ".&").asSymbol] = PTRhythmOp(name ++ ".&", 0, server, 4, i + 0.5);
			ctx[(name ++ ".E").asSymbol] = PTRhythmOp(name ++ ".E", 0, server, 4, i + 0.25);
			ctx[(name ++ ".A").asSymbol] = PTRhythmOp(name ++ ".A", 0, server, 4, i + 0.75);
			ctx[(name ++ ".PL").asSymbol] = PTRhythmOp(name ++ ".PL", 0, server, 4, i + 0.333);
			ctx[(name ++ ".ET").asSymbol] = PTRhythmOp(name ++ ".PL", 0, server, 4, i + 0.666);
		}
	}

	init {
		this.reset;
		ctx = ();
		this.initBusses(ctx);
		this.initBeats(ctx);
		if (out_proxy == nil, {
			out_proxy = NodeProxy.new(server, \audio, 2);
			out_proxy.source = { (param_busses[18].kr * \in.ar([0, 0])).tanh };
		});
		description = PTDescription.new(6);
		scripts = Array.new(numScripts+1);
		numScripts.do { |i|
			var script = PTScript.new(scriptSize, ctx);
			var oldCtx = ctx;
			scripts.add(script);
			ctx = ();
			ctx.parent = oldCtx;
			5.do { |nargs|
				var scriptOp;
				var name = "$" ++ (i + 1);
				if (nargs > 0, { name = (name ++ "." ++ nargs) });
				scriptOp = PTScriptOp.new(server, name, nargs, parser, script);
				ctx[name.asSymbol] = scriptOp;
			}
		};
		scripts.add(description);
		main = PTScriptNet.new(server: server, parser: parser, lines: [],
			args: [PTNode.new(PTInOp.new, [], nil)], script: scripts[numScripts-1]);
	}

	defaultQuant_ { |q|
		ctx['defaultQuant'] = q;
	}

	defaultQuant{
		^if (ctx.includesKey('defaultQuant'), {ctx['defaultQuant'];}, {1/16});
	}

	defaultFadeTime_ { |t|
		ctx['defaultFadeTime'] = t;
	}

	defaultFadeTime{
		^if (ctx.includesKey('defaultFadeTime'), {ctx['defaultFadeTime'];}, {0.01});
	}

	replace { |script, index, line, topLevel=true, callback|
		this.reset;
		scripts[script].replace(index, line, topLevel: topLevel, callback: callback);
	}

	insertPassthrough { |script, index, topLevel=true, callback|
		this.reset;
		scripts[script].insertPassthrough(index, topLevel: topLevel, callback: callback);
	}

	setParam { |param, v|
		param_busses[param].value = v;
	}

	removeAt { |script, index, topLevel=true, callback=nil|
		this.reset;
		scripts[script].removeAt(index, topLevel: topLevel, callback:callback);
	}

	add { |script, line, topLevel=true, callback|
		this.reset;
		scripts[script].add(line, topLevel: topLevel, callback: callback);
	}

	printOn { | stream |
		description.lines.do { |l|
			stream << l << "\n";
		};
		stream << "\n";
		numScripts.do { |i|
			var script = scripts[i];
			stream << "#" << (i + 1) << "\n";
			script.lines.do { |l, i|
				stream << l << "," << script.fadeTimes[i] << "," << script.quants[i] <<"\n";
			};
			stream << "\n";
		}
    }

	setFadeTime { |script, index, time|
		scripts[script].setFadeTime(index, time);
	}

	getFadeTime { |script, index|
		^scripts[script].getFadeTime(index);
	}

	setQuant { |script, index, q|
		scripts[script].setQuant(index, q);
	}

	getQuant { |script, index|
		^scripts[script].getQuant(index);
	}

	clear { |callback|
		this.reset;
		Routine({
			var latch;
			out_proxy.set(\in, [0,0]);
			server.sync;
			latch = PTCountdownLatch(numScripts, {
				PTDbg << "Clear done\n";
				callback.value;
			});
			PTDbg << "CLEARING old script data\n";
			PTDbg << "Free main\n";
			main.free;
			PTDbg << "Clear scripts\n";
			description.clear;
			scripts.do { |s|
				s.clear(topLevel: false, callback: latch);
			};
		}).play;
	}

	clearFully { |callback|
		this.clear({
			PTDbg << "Free busses\n";
			audio_busses.do { |b| b.free };
			control_busses.do { |b| b.free };
			out_proxy.clear;
			callback.value;
			// Removed because I think norns calls
			// alloc before it is done with free.
			// SynthDef.removeAt(\tick);
		});
	}

	load { |str, callback, errCallback|
		this.reset;
		this.clear({
			PTDbg << "Done with clear\n";
			this.loadOnly(str, {
				Routine({
					try {
						server.sync;
						out_proxy.set(\in, main.out);
						callback.value;
					} { |e|
						errCallback.value(e);
					};
				}).play;
			}, errCallback);
		});
	}

	loadHelper{ |scriptChunks, scriptIndex, topCallback, errCallback|
		var callback = if (scriptIndex == (numScripts - 1), {
			topCallback
		}, {
			{
				PTDbg << "Done with script " << scriptIndex << " loading next\n";
				this.loadHelper(scriptChunks, scriptIndex+1, topCallback, errCallback);
			}
		});
		PTDbg << "loading script " << scriptIndex << " with " << scriptChunks[scriptIndex] << "\n";
		try {
			scripts[scriptIndex].load(scriptChunks[scriptIndex], topLevel: false, callback: callback);
		} { |e|
			PTDbg << "Error on load; calling error callback\n";
			errCallback.value(e);
		};
	}

	loadOnly { |str, callback, errCallback|
		var lines = str.split($\n);
		var curScript = nil;
		var scriptChunks;
		var myErrCallback = { |err|
			this.clear({errCallback.value(err);});
		};
		PTDbg << "INITIALIZING new script data\n";
		this.init;
		scriptChunks = Array.fill(numScripts, {List.new});
		lines.do { |l|
			case {l[0] == $#} {
				curScript = (l[1..].asInteger - 1);
			}
			{l == ""} {
				// pass
			}
			{curScript == nil} {
				description.add(l);
			}
			{true} {
				scriptChunks[curScript].add(l);
			};
		};
		PTDbg << "SCRIPT CHUNKS " << scriptChunks << "\n";
		this.loadHelper(scriptChunks, 0, {
			if (this.out.rate != \audio, {
				//this.init;
				myErrCallback.value(PTCheckError.new("Output of loaded script was not audio"));
			}, {
				callback.value;
			});
		}, myErrCallback);
	}

	out {
		^out_proxy;
	}

	*zeroNode {
		^PTNode.new(PTLiteral.new(0), [])
	}

	*randId {
		^"".catList([
			consonants, vowels, consonants,
			consonants, vowels, consonants,
			consonants, vowels, consonants].collect({ |x| x.choose }));
	}

}

// norns glue
Engine_Phonotype : CroneEngine {
	classvar luaOscPort = 10111;

	var pt; // a Phonotype
	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}



	alloc {
		var luaOscAddr = NetAddr("localhost", luaOscPort);
		var executeAndReport = { |i, s, f, msg=nil|
			var e = nil;
			var cb = {
				// The "/report" message is:
				// int - request id, the one that made us report on it
				// int - script that we're reporting about, 0-indexed
				// string - error, if any
				// string - current newline-separated lines of that script
				luaOscAddr.sendMsg("/report", i, s, (e ? msg ? ""), "".catList(pt.scripts[s].lines.collect({ |l| l ++ "\n" })));
			};
			try {
				f.value(cb);
			} { |err|
				err.reportError;
				e = err.errorString;
				PTDbg << "Reporting error to user " << e << "\n";
				cb.value
			};

		};
		//  :/
		pt = PT.new(context.server);
		pt.load("", {
			PTDbg << "Initialized\n";
			pt.out.play;
		}, {
			PTDbg << "Boo\n";
		});

		this.addCommand("load_scene", "iis", { arg msg;
			PTDbg << "Engine load\n";
			executeAndReport.value(msg[1].asInt, msg[2].asInt, { |cb|
				pt.load(msg[3].asString, true, cb);
			});
		});

		this.addCommand("dump", "", {
			var c = CollStream.new;
			c << pt;
			luaOscAddr.sendMsg("/save", c.collection);
		});

		this.addCommand("insert_passthrough", "iii", { arg msg;
			executeAndReport.value(msg[1].asInt, msg[2].asInt, { |cb| pt.insertPassthrough(msg[2].asInt, msg[3].asInt, true, cb)});
		});

		this.addCommand("replace", "iiis", { arg msg;
			executeAndReport.value(msg[1].asInt, msg[2].asInt, { |cb|
				pt.replace(msg[2].asInt, msg[3].asInt, msg[4].asString, true, cb)
			});
		});

		this.addCommand("add", "iis", { arg msg;
			executeAndReport.value(msg[1].asInt, msg[2].asInt, { |cb|
				pt.add(msg[2].asInt, msg[3].asString, true, cb)
			});
		});

		this.addCommand("set_param", "if", { arg msg;
			pt.setParam(msg[1].asInt, msg[2].asFloat);
		});

		this.addCommand("remove", "iii", { arg msg;
			executeAndReport.value(msg[1].asInt, msg[2].asInt, { |cb|
				pt.removeAt(msg[2].asInt, msg[3].asInt, true, cb)
			});
		});

		this.addCommand("fade_time", "iiif", { arg msg;
			var prevFadeTime = pt.getFadeTime(msg[2].asInt, msg[3].asInt);
			var newFadeTime = (msg[4].asFloat * prevFadeTime).clip(0.005, 60);
			executeAndReport.value(msg[1].asInt, msg[2].asInt, { |cb|
				pt.setFadeTime(msg[2].asInt, msg[3].asInt, newFadeTime);
				cb.value;
			}, "fade time: " ++ newFadeTime.asStringPrec(3));
		});

		this.addCommand("quant", "iiii", { arg msg;
			var prevQuant = pt.getQuant(msg[2].asInt, msg[3].asInt);
			var prevQuantIndex = if(prevQuant < 1, {((-1 / prevQuant) + 2).round}, {prevQuant.round});
			var newQuantIndex = (msg[4].asInt + prevQuantIndex).clip(-126, 128);
			var newQuant = if (newQuantIndex <= 0, {-1 / (newQuantIndex - 2)}, {newQuantIndex});
			executeAndReport.value(msg[1].asInt, msg[2].asInt, { |cb|
				pt.setQuant(msg[2].asInt, msg[3].asInt, newQuant);
				cb.value;
			}, "schedule on: " ++ newQuant.asStringPrec(3));
		});

		this.addCommand("default_quant", "f", { |msg|
			pt.defaultQuant = msg[1].asFloat;
		});

		this.addCommand("default_fade_time", "f", { |msg|
			pt.defaultFadeTime = msg[1].asFloat;
		});

		this.addCommand("just_report", "ii", { arg msg;
			executeAndReport.value(msg[1].asInt, msg[2].asInt, {|cb| cb.value});
		});

		this.addCommand("tempo_sync", "ff", { arg msg;
			var beats = msg[1].asFloat;
			var tempo = msg[2].asFloat;
			var beatDifference = beats - TempoClock.default.beats;
			var nudge = beatDifference % 4;
			if (nudge > 2, {nudge = nudge - 4});
			if ( (tempo != TempoClock.default.tempo) || (nudge.abs > 1), {
				TempoClock.default.beats = TempoClock.default.beats + nudge;
				TempoClock.default.tempo = tempo;
			}, {
				TempoClock.default.beats = TempoClock.default.beats + (0.05 * nudge);
			});
			// Set M to be the duration of a beat.
			pt.setParam(16, 1/tempo);
		});

		this.addCommand("debug", "i", { |msg|
			PTDbg.debug = (msg[1].asInt > 0);
			PTDbg.slow = true;
		});
	}

	free {
		pt.clearFully;
	}
}


// [x] Fix race conditions
// [x] Reintegrate fixed race conditions with norns
// [ ] Write a replacement for NodeProxy that doesn't restart its synth when the input is set -- use a bus on input
// [ ] Figure out why sometimes using busses is buggy and/or does not clean up old connections & fix
// [ ] Adjust tests for fixed race conditions
// [x] Each Script keeps track of its Nets in `refs`.
// [x] Change edits to be two-phase: 1. Typecheck, 2. Commit.
// [x] Give a Net a free method.
// [x] When a Script line is edited, make the same edits to each Net. First do all Typechecks, then do all Commits.
// [x] Full multi-script setup with editing.
// [x] Script op
// [x] When a ScriptNet ends up with a `propagate` operation that propagates all the way to the end of script, blow up the calling line and replace it entire.
//    * The problem is that the "replace entire" operation needs to see the *new version* of the line. Figure out how to put that in the context.
// [x] Check for various leaks
// [x] When a Script is called, that generates a new Net. Link the Script to the Net, so it can edit the net when called. Keep the net in a per-line `resources` slot. On replacing or deleting a line, free all old `resources` after the xfade time.
// [x] Busses, both private and global
// [x] Output stage
// [ ] Buffer ops
// [x] TANH, FOLD, CLIP, SOFTCLIP, SINEFOLD
// [x] -, /
// [x] Rhythm ops of some kind.
// [x] Norns param ops
// [x] Clock sync with Norns
// [ ] Load and save from Norns
// [ ] Norns hz, gate for basic midi
// [x] Envelopes: PERC, AR, ADSR
// [x] Sequencer ops
// [x] Sample and hold
// [x] Pitch ops
// [x] L.MIX
// [ ] L.SERIES
// [ ] Polyphonic midi ops???
