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

#pragma once

@class OFUser;
#import "OFResource.h"

/////////////////////////////////////////////////////////////////////////////////////
/// The public interface to OFForumPost allows you to see replies to developer annoucements
/////////////////////////////////////////////////////////////////////////////////////
@interface OFForumPost : OFResource
{
	OFUser* author;
	NSString* body;
	NSDate* date;
	NSString* discussionId;
	BOOL isDiscussionConversation;
}

/////////////////////////////////////////////////////////////////////////////////////
/// The OFUser which is the author of this reply
/////////////////////////////////////////////////////////////////////////////////////
@property (nonatomic, readonly) OFUser* author;

/////////////////////////////////////////////////////////////////////////////////////
/// The body text of the reply
/////////////////////////////////////////////////////////////////////////////////////
@property (nonatomic, readonly) NSString* body;

/////////////////////////////////////////////////////////////////////////////////////
/// The date the reply was made
/////////////////////////////////////////////////////////////////////////////////////
@property (nonatomic, readonly) NSDate* date;

/////////////////////////////////////////////////////////////////////////////////////
/// @internal
/////////////////////////////////////////////////////////////////////////////////////
@property (retain) NSString* discussionId;
@property (nonatomic, readonly) BOOL isDiscussionConversation;

@end
