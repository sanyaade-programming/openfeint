//  Copyright 2009-2010 Aurora Feint, Inc.
// 
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//  
//  	http://www.apache.org/licenses/LICENSE-2.0
//  	
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

#import "OFResourceField.h"
#import "OFChallengeToUser.h"
#import "OFChallengeService.h"
#import "OFChallenge.h"
#import "OFUser.h"
#import "OFLog.h"
#import "OFDependencies.h"

static id sharedDelegate = nil;

@interface OFChallengeToUser (Private)
- (void)_completeSuccess;
- (void)_completeFail;
- (void)_rejectSuccess;
- (void)_rejectFail;
@end

@implementation OFChallengeToUser

@synthesize challenge, result, resultDescription, recipient, isCompleted, hasBeenViewed, attempts;
@synthesize hasDecrementedChallengeCount;

+ (void)setDelegate:(id<OFChallengeToUserDelegate>)delegate;
{
	sharedDelegate = delegate;
	
	if(sharedDelegate == nil)
	{
		[OFRequestHandlesForModule cancelAllRequestsForModule:[OFChallengeToUser class]];
	}
}

+ (OFChallengeToUser*)readFromFile:(NSString*)fileName;
{
	return [OFChallengeService readChallengeToUserFromFile:fileName];
}

- (OFRequestHandle*)completeWithResult:(OFChallengeResult)challengeResult
{
	if(challengeResult == kChallengeIncomplete)
	{
		OFLogError(@"You cannot pass kChallengeIncomplete to OFChallegeToUser's completeWithResult: method");
		return nil;
	}
	result = challengeResult;
	
	OFRequestHandle* handle = nil;
	
	handle = [OFChallengeService submitChallengeResult:self.resourceId
												result:challengeResult
									 resultDescription:self.resultDescription
                                   onSuccessInvocation:[OFInvocation invocationForTarget:self selector:@selector(_completeSuccess)]
                                   onFailureInvocation:[OFInvocation invocationForTarget:self selector:@selector(_completeFail)]];
//											 onSuccess:OFDelegate(self, @selector(_completeSuccess))
//											 onFailure:OFDelegate(self, @selector(_completeFail))];
	
	[OFRequestHandlesForModule addHandle:handle forModule:[OFChallengeToUser class]];
	return handle;
}

- (OFRequestHandle*)reject
{
	OFRequestHandle* handle = nil;
	handle = [OFChallengeService rejectChallenge:self.resourceId
                             onSuccessInvocation:[OFInvocation invocationForTarget:self selector:@selector(_rejectSuccess)]
                             onFailureInvocation:[OFInvocation invocationForTarget:self selector:@selector(_rejectFail)]];
//									   onSuccess:OFDelegate(self, @selector(_rejectSuccess))
//									   onFailure:OFDelegate(self, @selector(_rejectFail))];
	
	[OFRequestHandlesForModule addHandle:handle forModule:[OFChallengeToUser class]];
	return handle;
}

- (void)displayCompletionWithData:(NSData*)resultData
		   reChallengeDescription:(NSString*)reChallengeDescription
{
	if(self.result == kChallengeIncomplete)
	{
		OFLogError(@"You cannot call OFChallengeToUser's displayCompletionWithData: method without calling completeWithResult: first");
		return;
	}
	
	[OFChallengeService displayChallengeCompletedModal:self
											resultData:resultData
									 resultDescription:self.resultDescription 
								reChallengeDescription:reChallengeDescription];
}

- (void)writeToFile:(NSString*)fileName
{
	[OFChallengeService writeChallengeToUserToFile:fileName challengeToUser:self];
}

- (void)_completeSuccess
{
	if (sharedDelegate && [sharedDelegate respondsToSelector:@selector(didCompleteChallenge:)])
	{
		[sharedDelegate didCompleteChallenge:self];
	}
}

- (void)_completeFail
{
	if (sharedDelegate && [sharedDelegate respondsToSelector:@selector(didFailCompleteChallenge:)])
	{
		[sharedDelegate didFailCompleteChallenge:self];
	}
}

- (void)_rejectSuccess
{
	if (sharedDelegate && [sharedDelegate respondsToSelector:@selector(didRejectChallenge:)])
	{
		[sharedDelegate didRejectChallenge:self];
	}
}

- (void)_rejectFail
{
	if (sharedDelegate && [sharedDelegate respondsToSelector:@selector(didFailRejectChallenge:)])
	{
		[sharedDelegate didFailRejectChallenge:self];
	}
}

+ (NSString*)getChallengeResultIconName:(OFChallengeResult)result
{
	if (result == kChallengeResultRecipientWon)
	{
		return @"OFChallengeIconWon.png";
	}
	else if (result == kChallengeResultRecipientLost)
	{
		return @"OFChallengeIconLost.png";
	}
	else if (result == kChallengeResultTie)
	{
		return @"OFChallengeIconTied.png";
	}
	return nil;
}

- (void)setResultDescription:(NSString*)value
{
	OFSafeRelease(resultDescription);
	resultDescription = [value retain];
}

- (NSString*)formattedResultDescription
{
	NSRange range = [resultDescription rangeOfString:@"%@"];
	if (range.location == NSNotFound)
	{
		return resultDescription;
	}
	else
	{
		NSString* stringToInject = (!recipient || [recipient isLocalUser]) ? @"You" : recipient.name;
		return [NSString stringWithFormat:resultDescription, stringToInject];
	}
}

- (void)setResultFromString:(NSString*)value
{
    OFLOCALIZECOMMENT("These are match values with 'win','lose', 'tie' below")
	if ([value isEqualToString:OFLOCALSTRING(@"win")])
	{
		result = kChallengeResultRecipientWon;
	}
	else if ([value isEqualToString:OFLOCALSTRING(@"lose")])
	{
		result = kChallengeResultRecipientLost;
	}
	else if ([value isEqualToString:OFLOCALSTRING(@"tie")])
	{
		result = kChallengeResultTie;
	}
}

- (void)setNumAttempts:(NSString*)value
{
	attempts = [value intValue];
}

- (void)setIsCompletedFromString:(NSString*)value
{
	isCompleted = [value boolValue];
}

- (void)setHasBeenViewed:(NSString*)value
{
	hasBeenViewed = [value boolValue];
}

- (void)setChallenge:(OFChallenge*)value
{
	OFSafeRelease(challenge);
	challenge = [value retain];
}

- (void)setRecipient:(OFUser*)value
{
	OFSafeRelease(recipient);
	recipient = [value retain];
}

- (NSString*)getIsCompletedAsString
{
	return [NSString stringWithFormat:@"%u", isCompleted];
}

- (NSString*)getHasBeenViewedAsString
{
	return [NSString stringWithFormat:@"%u", hasBeenViewed];
}

- (NSString*)getNumAttemptsAsString
{
	return [NSString stringWithFormat:@"%u", attempts];
}

- (NSString*)getResultAsString
{
	if (result == kChallengeResultRecipientWon)
	{
		return OFLOCALSTRING(@"win");
	}
	else if (result == kChallengeResultRecipientLost)
	{
		return OFLOCALSTRING(@"lose");
	}
	else if (result == kChallengeResultTie)
	{
		return OFLOCALSTRING(@"tie");
	}
	return @"";
}

- (OFResource*)getChallenge
{
	return challenge;
}

- (OFResource*)getRecipient
{
	return recipient;
}

+ (OFService*)getService;
{
	return [OFChallengeService sharedInstance];
}


+ (NSString*)getResourceName
{
	return @"challenges_user";
}

+ (NSString*)getResourceDiscoveredNotification
{
	return @"openfeint_challenge_to_user_discovered";
}

- (void) dealloc
{
	OFSafeRelease(challenge);
	OFSafeRelease(resultDescription);
	OFSafeRelease(recipient);
	[super dealloc];
}

+ (NSDictionary*)dataDictionary
{
    static NSDictionary*sDataDictionary = nil;
    if(!sDataDictionary)
    {
        sDataDictionary = [[NSDictionary dictionaryWithObjectsAndKeys:
[OFResourceField nestedResourceSetter:@selector(setChallenge:) getter:@selector(getChallenge) klass:[OFChallenge class]], @"challenge",
[OFResourceField nestedResourceSetter:@selector(setRecipient:) getter:@selector(getRecipient) klass:[OFUser class]], @"user",
[OFResourceField fieldSetter:@selector(setResultDescription:) getter:@selector(resultDescription)], @"result_text",
[OFResourceField fieldSetter:@selector(setResultFromString:) getter:@selector(getResultAsString)], @"result",
[OFResourceField fieldSetter:@selector(setIsCompletedFromString:) getter:@selector(getIsCompletedAsString)], @"completed_at",
[OFResourceField fieldSetter:@selector(setHasBeenViewed:) getter:@selector(getHasBeenViewedAsString)], @"viewed",
[OFResourceField fieldSetter:@selector(setNumAttempts:) getter:@selector(getNumAttemptsAsString)], @"attempts",
        nil] retain];
    }
    return sDataDictionary;
}
@end
